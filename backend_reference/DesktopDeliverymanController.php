<?php

namespace App\Http\Controllers\Api;

use App\Enums\OrderStatusEnum;
use App\Facades\PaymentService;
use App\Http\Controllers\Controller;
use App\Models\Tenant\Order;
use App\Models\Tenant\Tenant;
use App\Models\Tenant\TenantDialog;
use App\Models\Tenant\TenantMessage;
use App\Models\Tenant\TenantUser;
use App\Services\Tenants\OrderDialogService;
use App\Services\Tenants\OrderService;
use Carbon\Carbon;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Schema;
use Illuminate\Validation\ValidationException;

class DesktopDeliverymanController extends Controller
{
    private const ACTIVE_STATUSES = [1, 4, 5];
    private const AVAILABLE_STATUSES = [0, 4, 5];

    /** Login is independent from the browser's tenant guard/session. */
    public function login(Request $request): JsonResponse
    {
        $data = $request->validate([
            'login' => ['required', 'string', 'max:190'],
            'password' => ['required', 'string', 'max:255'],
            'tenant' => ['nullable', 'string', 'max:190'],
        ]);

        $query = TenantUser::query()->where(function ($q) use ($data) {
            $q->where('email', $data['login'])->orWhere('phone', $data['login']);
        });

        if (!empty($data['tenant'])) {
            $tenant = ctype_digit($data['tenant'])
                ? Tenant::query()->find($data['tenant'])
                : Tenant::query()->where('slug', $data['tenant'])->first();
            if (!$tenant) {
                throw ValidationException::withMessages(['tenant' => 'Указанный магазин/tenant не найден.']);
            }
            $query->where('tenant_id', $tenant->id);
        }

        $matches = $query->limit(2)->get();
        if ($matches->count() !== 1) {
            $message = $matches->isEmpty()
                ? 'Неверный логин или пароль.'
                : 'Найдено несколько аккаунтов. Укажите Tenant ID или slug.';
            throw ValidationException::withMessages(['login' => $message]);
        }

        /** @var TenantUser $user */
        $user = $matches->first();
        if (!Hash::check($data['password'], $user->getAuthPassword())) {
            throw ValidationException::withMessages(['login' => 'Неверный логин или пароль.']);
        }
        if (!(bool) $user->is_active || $user->blocked_at) {
            return response()->json(['success' => false, 'message' => 'Аккаунт отключён или заблокирован.'], 403);
        }
        if (!$this->isCourier($user)) {
            return response()->json(['success' => false, 'message' => 'У аккаунта нет роли курьера.'], 403);
        }
        if (!method_exists($user, 'createToken')) {
            return response()->json(['success' => false, 'message' => 'Для TenantUser не подключён Sanctum HasApiTokens.'], 500);
        }

        $token = $user->createToken('courier-desktop', ['deliveryman:*']);
        return response()->json([
            'success' => true,
            'data' => [
                'token' => $token->plainTextToken,
                'user' => $this->userPayload($user),
            ],
        ]);
    }

    public function me(Request $request): JsonResponse
    {
        return response()->json(['success' => true, 'data' => ['user' => $this->userPayload($request->user())]]);
    }

    public function logout(Request $request): JsonResponse
    {
        $token = $request->user()->currentAccessToken();
        if ($token) $token->delete();
        return response()->json(['success' => true, 'message' => 'Вы вышли из приложения.']);
    }

    public function dashboard(Request $request): JsonResponse
    {
        $user = $request->user();
        $base = $this->shopFiltered(Order::query(), $user);
        $active = (clone $base)->where('deliveryman_id', $user->id)->whereIn('status', self::ACTIVE_STATUSES)->count();
        $completed = (clone $base)->where('deliveryman_id', $user->id)->where('status', OrderStatusEnum::Completed->value)->count();
        $available = (clone $base)->whereNull('deliveryman_id')->whereIn('status', self::AVAILABLE_STATUSES)
            ->whereDate('created_at', '>=', Carbon::today('Europe/Moscow'))->count();
        $earned = (clone $base)->where('deliveryman_id', $user->id)->where('status', OrderStatusEnum::Completed->value)
            ->whereMonth('delivered_at', Carbon::now('Europe/Moscow')->month)
            ->whereYear('delivered_at', Carbon::now('Europe/Moscow')->year)->sum('delivery_price');

        return response()->json(['success' => true, 'data' => ['deliveryman' => [
            'id' => $user->id, 'name' => $user->name ?: 'Курьер', 'phone' => $user->phone,
            'status' => $this->isOnline($user) ? 'online' : 'offline', 'earned' => (float) $earned,
            'active_orders_count' => $active, 'available_orders_count' => $available,
            'completed_orders_count' => $completed,
        ]]]);
    }

    public function availableOrders(Request $request): JsonResponse
    {
        $dates = $this->dateRange($request);
        $orders = $this->shopFiltered(Order::query(), $request->user())
            ->whereNull('deliveryman_id')->whereIn('status', self::AVAILABLE_STATUSES)
            ->whereDate('created_at', '>=', $dates[0])->whereDate('created_at', '<=', $dates[1])
            ->with(['tenant', 'location'])->orderByDesc('created_at')->limit(100)->get();
        return response()->json(['success' => true, 'data' => $orders->map(fn ($o) => $this->orderPayload($o, 'available'))]);
    }

    public function activeOrders(Request $request): JsonResponse
    {
        $dates = $this->dateRange($request); $user = $request->user();
        $orders = $this->shopFiltered(Order::query(), $user)->where('deliveryman_id', $user->id)
            ->whereIn('status', self::ACTIVE_STATUSES)->whereDate('created_at', '>=', $dates[0])
            ->whereDate('created_at', '<=', $dates[1])->with(['tenant', 'location'])
            ->orderByDesc('created_at')->limit(100)->get();
        return response()->json(['success' => true, 'data' => $orders->map(fn ($o) => $this->orderPayload($o, 'active'))]);
    }

    public function completedOrders(Request $request): JsonResponse
    {
        $dates = $this->dateRange($request); $user = $request->user();
        $orders = $this->shopFiltered(Order::query(), $user)->where('deliveryman_id', $user->id)
            ->where('status', OrderStatusEnum::Completed->value)->whereDate('delivered_at', '>=', $dates[0])
            ->whereDate('delivered_at', '<=', $dates[1])->with(['tenant', 'location'])
            ->orderByDesc('delivered_at')->limit(100)->get();
        return response()->json(['success' => true, 'data' => $orders->map(fn ($o) => $this->orderPayload($o, 'completed'))]);
    }

    public function toggleStatus(Request $request): JsonResponse
    {
        $data = $request->validate(['is_online' => ['required', 'boolean']]);
        $user = $request->user();
        if (!Schema::hasColumn($user->getTable(), 'is_online')) {
            return response()->json(['success' => false, 'message' => 'В таблице tenant_users отсутствует колонка is_online. Добавьте миграцию.'], 409);
        }
        $user->forceFill(['is_online' => $data['is_online']])->save();
        return response()->json(['success' => true, 'is_online' => (bool) $user->fresh()->getAttribute('is_online')]);
    }

    /** Atomic assignment: prevents two couriers accepting the same order. */
    public function acceptOrder(Request $request, int $id): JsonResponse
    {
        $user = $request->user();
        $order = DB::transaction(function () use ($id, $user) {
            $order = $this->shopFiltered(Order::query(), $user)->whereKey($id)->lockForUpdate()->first();
            if (!$order || $order->deliveryman_id !== null || !in_array((int) $order->status, self::AVAILABLE_STATUSES, true)) return null;
            $order->deliveryman_id = $user->id;
            $order->status = OrderStatusEnum::InDelivery->value;
            $order->save();
            return $order;
        });
        if (!$order) return response()->json(['success' => false, 'message' => 'Заказ уже забрали или он недоступен.'], 409);
        if ($order->dialog_id) {
            try { app(OrderDialogService::class)->addSystemMessage($order, "🚚 Курьер {$user->name} принял ваш заказ и готовится к выезду!", 'status_change', ['deliveryman_name' => $user->name]); }
            catch (\Throwable $e) { report($e); }
        }
        return response()->json(['success' => true, 'message' => 'Заказ принят', 'order_id' => $order->id]);
    }

    public function changeStatus(Request $request, int $id): JsonResponse
    {
        $data = $request->validate(['status' => ['required', 'integer', 'in:1,4,5']]);
        $order = Order::query()->whereKey($id)->where('deliveryman_id', $request->user()->id)->firstOrFail();
        $order->status = (int) $data['status']; $order->save();
        return response()->json(['success' => true, 'message' => 'Статус заказа обновлён']);
    }

    public function confirmDelivery(Request $request, int $id): JsonResponse
    {
        $user = $request->user();
        $order = DB::transaction(function () use ($id, $user) {
            $order = Order::whereKey($id)->where('deliveryman_id', $user->id)->lockForUpdate()->first();
            if (!$order || (int) $order->status === OrderStatusEnum::Completed->value) return null;
            $order->status = OrderStatusEnum::Completed->value; $order->delivered_at = now(); $order->save();
            return $order;
        });
        if (!$order) return response()->json(['success' => false, 'message' => 'Заказ не найден или уже завершён.'], 404);
        if ($order->dialog_id) {
            try { app(OrderDialogService::class)->addSystemMessage($order, '🎉 Ваш заказ успешно доставлен! Приятного аппетита.', 'status_change'); }
            catch (\Throwable $e) { report($e); }
        }
        return response()->json(['success' => true, 'message' => 'Доставка подтверждена', 'earned' => (float) $order->delivery_price]);
    }

    public function requestDeliveryPayment(Request $request, int $id): JsonResponse
    {
        $data = $request->validate(['amount'=>['required','numeric','min:1'],'description'=>['required','string','max:255']]);
        $order = Order::whereKey($id)->where('deliveryman_id', $request->user()->id)->firstOrFail();
        try {
            $url = PaymentService::call()->generateSimplePaymentLink([
                'order_id'=>$order->id,'amount'=>$data['amount'],'description'=>$data['description'],
                'name'=>$order->receiver_name,'phone'=>$order->receiver_phone,'customer_key'=>(string)$order->tenant_user_id,
            ]);
            return response()->json(['success'=>true,'data'=>['url'=>$url,'amount'=>(float)$data['amount']]]);
        } catch (\Throwable $e) {
            report($e);
            return response()->json(['success'=>false,'message'=>'Не удалось сформировать ссылку. Проверьте настройки эквайринга.'],500);
        }
    }

    public function calculateShopDelivery(Request $request, int $id): JsonResponse
    {
        $data = $request->validate(['lat'=>['required','numeric','between:-90,90'],'lng'=>['required','numeric','between:-180,180']]);
        $shop = $this->accessibleShop($id, $request->user());
        if (!$shop) return response()->json(['success'=>false,'message'=>'Магазин недоступен для этого курьера.'],403);
        $config = $shop->settings ?? [];
        $shopCoords = $shop->shop_coords ?: (data_get($config,'shop_coords') ?: data_get($config,'shop.shop_coords'));
        if (!$shopCoords) return response()->json(['success'=>false,'message'=>'Координаты заведения не указаны в настройках.'],422);
        $meters = \App\Services\Tenants\GEOService::call()->getDistance((float)$data['lat'],(float)$data['lng'],$shopCoords);
        $coef = (float)(env('DISTANCE_COEF',1) ?: 1); $km = round(((float)$meters / 1000) * $coef,2);
        $perKm = (float)(data_get($config,'shop.price_per_km',data_get($config,'price_per_km',80)));
        $zones = data_get($config,'shop.delivery_zones',data_get($config,'delivery_zones',[])) ?: [];
        usort($zones,fn($a,$b)=>(float)($a['radius']??0)<=>(float)($b['radius']??0));
        $parsePrice = function($value) {
            if (is_numeric($value)) return (float)$value;
            $value = mb_strtolower((string)$value);
            if (str_contains($value,'бесплатно') || str_contains($value,'free')) return 0.0;
            preg_match('/\d+([\.,]\d+)?/u',$value,$m);
            return isset($m[0]) ? (float)str_replace(',','.',$m[0]) : 0.0;
        };
        $zoneName = 'Базовый тариф'; $outside = false; $base = 0.0;
        if ($zones) {
            $found = false;
            foreach ($zones as $zone) {
                if ($km <= (float)($zone['radius']??0)) { $base=$parsePrice($zone['price']??0); $zoneName=$zone['name']??'Зона'; $found=true; break; }
            }
            if (!$found) { $outside=true; $last=end($zones); $base=$parsePrice($last['price']??0); $zoneName=($last['name']??'Дальняя зона').' (сверх лимита)'; }
        } else { $base=(float)data_get($config,'shop.min_base_delivery_price',data_get($config,'min_base_delivery_price',100)); $zoneName='Без зон (линейный расчёт)'; }
        $distanceCost=$km*$perKm; $total=round($base+$distanceCost,2);
        return response()->json(['success'=>true,'data'=>['shop_name'=>$shop->name,'distance_km'=>$km,'delivery_price'=>$total,'is_outside_zones'=>$outside,
            'breakdown'=>['zone_name'=>$zoneName,'base_price'=>$base,'distance_km'=>$km,'price_per_km'=>$perKm,'distance_cost'=>round($distanceCost,2),'total'=>$total]]]);
    }

    public function generateShopPaymentLink(Request $request, int $id): JsonResponse
    {
        $data=$request->validate(['amount'=>['required','numeric','min:1'],'description'=>['required','string','max:255']]);
        $shop=$this->accessibleShop($id,$request->user());
        if (!$shop) return response()->json(['success'=>false,'message'=>'Магазин недоступен для этого курьера.'],403);
        try {
            $url=PaymentService::call()->generateSimplePaymentLink(['order_id'=>'DELIVERY_'.$shop->id.'_'.time(),'amount'=>$data['amount'],
                'description'=>$data['description'],'name'=>'Клиент доставки','phone'=>'','customer_key'=>'deliveryman_calc']);
            return response()->json(['success'=>true,'data'=>['url'=>$url,'amount'=>(float)$data['amount']]]);
        } catch (\Throwable $e) { report($e); return response()->json(['success'=>false,'message'=>'Не удалось сформировать ссылку на оплату.'],500); }
    }

    private function accessibleShop(int $id, TenantUser $user): ?Tenant
    {
        $meta = $this->normaliseUserMeta($user->meta ?? []);
        $allowed = array_values(array_unique(array_merge(
            $this->normaliseShopIds(data_get($meta, 'assigned_shops', [])),
            $this->normaliseShopIds(data_get($meta, 'settings.assigned_shops', [])),
            $this->normaliseShopIds(data_get($meta, 'settings.delivery_shops', data_get($meta, 'delivery_shops', []))),
            $user->tenant_id ? [(int) $user->tenant_id] : []
        )));
        if (!$this->isSuperAdmin($user) && !in_array($id, $allowed, true)) return null;
        $query = Tenant::query()->whereKey($id);
        if (Schema::hasColumn((new Tenant())->getTable(), 'is_active')) $query->where('is_active', true);
        return $query->first();
    }

    private function normaliseUserMeta($meta): array
    {
        if (is_string($meta)) {
            $decoded = json_decode($meta, true);
            return is_array($decoded) ? $decoded : [];
        }
        if ($meta instanceof \Illuminate\Support\Collection) return $meta->toArray();
        if (is_object($meta)) return (array) $meta;
        return is_array($meta) ? $meta : [];
    }

    private function normaliseShopIds($value): array
    {
        if (is_string($value)) {
            $decoded = json_decode($value, true);
            $value = is_array($decoded) ? $decoded : preg_split('/[,;\s]+/', trim($value), -1, PREG_SPLIT_NO_EMPTY);
        } elseif ($value instanceof \Illuminate\Support\Collection) {
            $value = $value->all();
        } elseif (is_object($value)) {
            $value = (array) $value;
        }
        if (!is_array($value)) return [];
        $ids = [];
        foreach ($value as $key => $item) {
            if (is_array($item) || is_object($item)) {
                $item = (array) $item;
                $item = $item['id'] ?? $item['tenant_id'] ?? $item['shop_id'] ?? null;
            } elseif (!is_numeric($item) && is_numeric($key) && $item) {
                $item = $key;
            }
            if (is_numeric($item) && (int) $item > 0) $ids[] = (int) $item;
        }
        return array_values(array_unique($ids));
    }

    public function updateLocation(Request $request): JsonResponse
    {
        $data = $request->validate(['latitude' => ['required','numeric','between:-90,90'], 'longitude' => ['required','numeric','between:-180,180']]);
        $ok = OrderService::call()->storeCoordsToOrder($data['latitude'], $data['longitude']);
        return response()->json(['success' => (bool) $ok, 'message' => $ok ? 'Координаты обновлены' : 'Нет активных заказов']);
    }

    public function getSettings(Request $request): JsonResponse
    {
        $meta = $request->user()->meta ?? [];
        return response()->json(['success' => true, 'data' => $meta['delivery_settings'] ?? ['auto_refresh' => false, 'refresh_interval' => 30, 'sound_enabled' => true]]);
    }

    public function saveSettings(Request $request): JsonResponse
    {
        $data = $request->validate(['auto_refresh' => ['required','boolean'], 'refresh_interval' => ['required','integer','min:30','max:3600'], 'sound_enabled' => ['required','boolean']]);
        $user = $request->user(); $meta = $user->meta ?? []; $meta['delivery_settings'] = $data;
        $user->update(['meta' => $meta]);
        return response()->json(['success' => true, 'data' => $data]);
    }

    public function getShops(Request $request): JsonResponse
    {
        $user = $request->user();
        $meta = $this->normaliseUserMeta($user->meta ?? []);
        $isAdmin = $this->isSuperAdmin($user);

        // Поддерживаем разные версии структуры meta: массивы ID, объекты и JSON-строки.
        $settingsMeta = data_get($meta, 'settings', []);
        if (is_string($settingsMeta)) { $decodedSettings = json_decode($settingsMeta, true); $settingsMeta = is_array($decodedSettings) ? $decodedSettings : []; }
        $assigned = array_values(array_unique(array_merge(
            $this->normaliseShopIds(data_get($meta, 'assigned_shops', [])),
            $this->normaliseShopIds(data_get($settingsMeta, 'assigned_shops', []))
        )));
        $selected = $this->normaliseShopIds(data_get($settingsMeta, 'delivery_shops', data_get($meta, 'delivery_shops', [])));
        $ownTenant = $user->tenant_id ? [(int) $user->tenant_id] : [];
        $allowedIds = array_values(array_unique(array_filter(array_merge($assigned, $selected, $ownTenant))));

        $query = Tenant::query();
        if (Schema::hasColumn((new Tenant())->getTable(), 'is_active')) {
            $query->where('is_active', true);
        }
        if (!$isAdmin) {
            if (!$allowedIds) {
                return response()->json(['success' => true, 'data' => [
                    'shops' => [], 'selected_ids' => [], 'assigned_ids' => [],
                    'is_restricted' => true, 'access_source' => 'no_assignments',
                ]]);
            }
            $query->whereIn('id', $allowedIds);
        }

        $shops = $query->orderBy('name')->get()->map(function ($shop) {
            $settings = $shop->settings ?? [];
            return [
                'id' => (int) $shop->id,
                'name' => $shop->name ?? $shop->title ?? ('Заведение ' . $shop->id),
                'slug' => $shop->slug,
                'address' => data_get($settings, 'shop.address') ?: data_get($settings, 'address') ?: 'Адрес не указан',
                'shop_coords' => $shop->shop_coords ?: (data_get($settings, 'shop_coords') ?: data_get($settings, 'shop.shop_coords')),
                'nearest_cities' => data_get($settings, 'nearest_cities') ?: data_get($settings, 'shop.nearest_cities'),
                'map_tiler' => data_get($settings, 'map_tiler') ?: data_get($settings, 'shop.map_tiler'),
            ];
        })->values();

        $selectedIds = $selected ?: ($isAdmin ? [] : $allowedIds);
        return response()->json(['success' => true, 'data' => [
            'shops' => $shops,
            'selected_ids' => array_map('intval', $selectedIds),
            'assigned_ids' => $allowedIds,
            'is_restricted' => !$isAdmin,
            'access_source' => $isAdmin ? 'super_admin' : ($assigned ? 'assigned_shops' : ($selected ? 'settings.delivery_shops' : 'tenant_id')),
        ]]);
    }

    public function saveShops(Request $request): JsonResponse
    {
        $data = $request->validate(['shop_ids' => ['present','array'], 'shop_ids.*' => ['integer','distinct','exists:tenants,id']]);
        $user = $request->user(); $meta = $this->normaliseUserMeta($user->meta ?? []);
        $activeQuery = Tenant::query();
        if (Schema::hasColumn((new Tenant())->getTable(), 'is_active')) $activeQuery->where('is_active', true);
        $allowed = $this->isSuperAdmin($user)
            ? $activeQuery->pluck('id')->map(fn($v)=>(int)$v)->all()
            : array_values(array_unique(array_merge(
                $this->normaliseShopIds(data_get($meta, 'assigned_shops', [])),
                $this->normaliseShopIds(data_get($meta, 'settings.assigned_shops', [])),
                $this->normaliseShopIds(data_get($meta, 'settings.delivery_shops', data_get($meta, 'delivery_shops', []))),
                $user->tenant_id ? [(int) $user->tenant_id] : []
            )));
        $ids = array_values(array_intersect(array_map('intval', $data['shop_ids']), $allowed));
        $settings = is_array($meta['settings'] ?? null) ? $meta['settings'] : [];
        $settings['delivery_shops'] = $ids; $meta['settings'] = $settings;
        $user->update(['meta' => $meta]);
        return response()->json(['success' => true, 'data' => ['selected_ids' => $ids, 'count' => count($ids)]]);
    }

    public function updateOrderDetails(Request $request, int $id): JsonResponse
    {
        $data = $request->validate(['distance_km' => ['nullable','numeric','min:0'], 'delivery_price' => ['nullable','numeric','min:0']]);
        $user = $request->user();
        $order = $this->shopFiltered(Order::query(), $user)->whereKey($id)->whereNull('deliveryman_id')->firstOrFail();
        $order->delivery_range = $data['distance_km'] ?? $order->delivery_range;
        $order->delivery_price = $data['delivery_price'] ?? $order->delivery_price;
        $order->save();
        return response()->json(['success' => true, 'data' => ['distance_km' => $order->delivery_range, 'delivery_price' => $order->delivery_price]]);
    }

    public function getDialogMessages(Request $request, int $dialogId): JsonResponse
    {
        $order = Order::where('dialog_id', $dialogId)->where('deliveryman_id', $request->user()->id)->firstOrFail();
        $messages = TenantMessage::where('dialog_id', $dialogId)->orderBy('created_at')->get()->map(fn($m) => [
            'id'=>$m->id,'sender_type'=>$m->sender_type,'sender_id'=>$m->sender_id,'message'=>$m->message,'meta'=>$m->meta,'created_at'=>$m->created_at?->toISOString()
        ]);
        return response()->json(['success' => true, 'data' => $messages]);
    }

    public function sendDialogMessage(Request $request, int $dialogId): JsonResponse
    {
        $data = $request->validate(['message' => ['required','string','max:1000']]); $user = $request->user();
        $order = Order::where('dialog_id', $dialogId)->where('deliveryman_id', $user->id)->firstOrFail();
        $message = TenantMessage::create(['tenant_id'=>$order->tenant_id,'dialog_id'=>$dialogId,'sender_type'=>'deliveryman','sender_id'=>$user->id,
            'message'=>$data['message'],'meta'=>['order_id'=>$order->id,'sender_name'=>$user->name ?? 'Курьер','type'=>'deliveryman_message'],'is_read'=>false]);
        TenantDialog::whereKey($dialogId)->update(['last_message_at'=>now()]);
        return response()->json(['success'=>true,'data'=>['id'=>$message->id,'sender_type'=>$message->sender_type,'message'=>$message->message,'created_at'=>$message->created_at?->toISOString()]]);
    }

    private function userPayload(TenantUser $user): array
    {
        return ['id'=>$user->id,'name'=>$user->name,'phone'=>$user->phone,'email'=>$user->email,'tenant_id'=>$user->tenant_id,
            'roles'=>$user->role_names ?? [],'is_online'=>$this->isOnline($user)];
    }

    private function isCourier(TenantUser $user): bool
    {
        $roles = array_map('strtolower', $user->role_names ?? []);
        $allowed = array_filter(array_map('trim', explode(',', (string) env('DESKTOP_DELIVERYMAN_ROLES', 'deliveryman,courier'))));
        return $this->isSuperAdmin($user) || (bool) array_intersect($roles, $allowed);
    }

    private function isSuperAdmin(TenantUser $user): bool
    {
        $roles = $user->role_names ?? [];
        if (is_string($roles)) $roles = preg_split('/[,;\s]+/', strtolower($roles), -1, PREG_SPLIT_NO_EMPTY);
        if ($roles instanceof \Illuminate\Support\Collection) $roles = $roles->all();
        return in_array('super_admin', array_map('strtolower', (array) $roles), true) || (bool) ($user->is_admin ?? false);
    }

    private function isOnline(TenantUser $user): bool
    {
        return Schema::hasColumn($user->getTable(), 'is_online') ? (bool) $user->getAttribute('is_online') : false;
    }

    private function dateRange(Request $request): array
    {
        $today = Carbon::today('Europe/Moscow')->toDateString();
        $data = $request->validate(['date_from'=>['nullable','date_format:Y-m-d'],'date_to'=>['nullable','date_format:Y-m-d']]);
        return [$data['date_from'] ?? $today, $data['date_to'] ?? $today];
    }

    private function shopFiltered($query, TenantUser $user)
    {
        $ids = array_map('intval', data_get($user->meta, 'settings.delivery_shops', []));
        if (!$ids) return $query->whereRaw('1 = 0');
        return $query->whereIn('tenant_id', $ids);
    }

    private function orderPayload(Order $order, string $kind): array
    {
        $location = $order->location; $address = $location
            ? trim(($location->city ? $location->city . ', ' : '') . ($location->address ?? '')) : 'Адрес не указан';
        return ['id'=>$order->id,'status'=>(int)$order->status,'tenant_name'=>$order->tenant?->name ?? 'Заведение',
            'tenant'=>['name'=>$order->tenant?->name ?? 'Заведение'],'address'=>$address,'lat'=>$location?->lat,'lng'=>$location?->lng,
            'distance_km'=>(float)($order->delivery_range ?? 0),'order_price'=>(float)($order->summary_price ?? 0),
            'summary_price'=>(float)($order->summary_price ?? 0),'delivery_price'=>(float)($order->delivery_price ?? 0),
            'created_at'=>$order->created_at?->toISOString(),'delivered_at'=>$order->delivered_at?->toISOString(),
            'product_details'=>$order->product_details,'receiver_name'=>$order->receiver_name,'receiver_phone'=>$order->receiver_phone,
            'dialog_id'=>$order->dialog_id,'info'=>$order->delivery_note];
    }
}
