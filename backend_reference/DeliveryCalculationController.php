<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Tenant\Tenant;
use App\Models\Tenant\TenantUser;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Schema;

/** Standalone delivery price calculation. Does not create or modify an order. */
class DeliveryCalculationController extends Controller
{
    public function calculate(Request $request, int $shopId): JsonResponse
    {
        $data = $request->validate([
            'origin_lat' => ['required', 'numeric', 'between:-90,90'],
            'origin_lng' => ['required', 'numeric', 'between:-180,180'],
            'destination_lat' => ['required', 'numeric', 'between:-90,90'],
            'destination_lng' => ['required', 'numeric', 'between:-180,180'],
            'origin_address' => ['nullable', 'string', 'max:500'],
            'destination_address' => ['nullable', 'string', 'max:500'],
        ]);

        $user = $request->user();
        $shop = $this->accessibleShop($shopId, $user);
        if (!$shop) {
            return response()->json(['success' => false, 'message' => 'Заведение недоступно для этого курьера.'], 403);
        }

        // Тарифы всегда читаются из настроек выбранного Tenant, а не из клиента.
        $config = $shop->settings ?? [];
        $origin = (string) $data['origin_lat'] . ',' . (string) $data['origin_lng'];
        try {
            $meters = \App\Services\Tenants\GEOService::call()->getDistance(
                (float) $data['destination_lat'],
                (float) $data['destination_lng'],
                $origin
            );
        } catch (\Throwable $e) {
            report($e);
            return response()->json(['success' => false, 'message' => 'Не удалось рассчитать расстояние между выбранными точками.'], 422);
        }

        $distanceCoefficient = (float) (env('DISTANCE_COEF', 1) ?: 1);
        $distanceKm = round(((float) $meters / 1000) * $distanceCoefficient, 2);
        $pricePerKm = (float) data_get($config, 'shop.price_per_km', data_get($config, 'price_per_km', 80));
        $zones = data_get($config, 'shop.delivery_zones', data_get($config, 'delivery_zones', [])) ?: [];
        if (!is_array($zones)) $zones = [];
        usort($zones, fn ($a, $b) => (float) ($a['radius'] ?? 0) <=> (float) ($b['radius'] ?? 0));

        $parsePrice = static function ($value): float {
            if (is_numeric($value)) return (float) $value;
            $value = mb_strtolower((string) $value);
            if (str_contains($value, 'бесплатно') || str_contains($value, 'free')) return 0.0;
            preg_match('/\d+([\.,]\d+)?/u', $value, $matches);
            return isset($matches[0]) ? (float) str_replace(',', '.', $matches[0]) : 0.0;
        };

        $basePrice = 0.0;
        $zoneName = 'Базовый тариф';
        $outside = false;
        if ($zones) {
            $found = false;
            foreach ($zones as $zone) {
                if ($distanceKm <= (float) ($zone['radius'] ?? 0)) {
                    $basePrice = $parsePrice($zone['price'] ?? 0);
                    $zoneName = $zone['name'] ?? 'Зона';
                    $found = true;
                    break;
                }
            }
            if (!$found) {
                $outside = true;
                $lastZone = end($zones);
                $basePrice = $parsePrice($lastZone['price'] ?? 0);
                $zoneName = ($lastZone['name'] ?? 'Дальняя зона') . ' (сверх лимита)';
            }
        } else {
            $basePrice = (float) data_get($config, 'shop.min_base_delivery_price', data_get($config, 'min_base_delivery_price', 100));
            $zoneName = 'Без зон (линейный расчёт)';
        }

        $distanceCost = round($distanceKm * $pricePerKm, 2);
        $total = round($basePrice + $distanceCost, 2);

        return response()->json([
            'success' => true,
            'data' => [
                'shop_id' => $shop->id,
                'shop_name' => $shop->name ?? $shop->title,
                'origin' => ['lat' => (float) $data['origin_lat'], 'lng' => (float) $data['origin_lng'], 'address' => $data['origin_address'] ?? null],
                'destination' => ['lat' => (float) $data['destination_lat'], 'lng' => (float) $data['destination_lng'], 'address' => $data['destination_address'] ?? null],
                'distance_km' => $distanceKm,
                'delivery_price' => $total,
                'is_outside_zones' => $outside,
                'breakdown' => [
                    'zone_name' => $zoneName,
                    'base_price' => $basePrice,
                    'distance_km' => $distanceKm,
                    'price_per_km' => $pricePerKm,
                    'distance_cost' => $distanceCost,
                    'total' => $total,
                ],
            ],
        ]);
    }

    private function accessibleShop(int $id, TenantUser $user): ?Tenant
    {
        $roles = $user->role_names ?? [];
        if (is_string($roles)) $roles = preg_split('/[,;\s]+/', strtolower($roles), -1, PREG_SPLIT_NO_EMPTY);
        if ($roles instanceof \Illuminate\Support\Collection) $roles = $roles->all();
        $isSuperAdmin = in_array('super_admin', array_map('strtolower', (array) $roles), true) || (bool) ($user->is_admin ?? false);
        $meta = $user->meta ?? [];
        if (is_string($meta)) { $decoded = json_decode($meta, true); $meta = is_array($decoded) ? $decoded : []; }
        elseif ($meta instanceof \Illuminate\Support\Collection) $meta = $meta->toArray();
        elseif (is_object($meta)) $meta = (array) $meta;
        $settingsMeta = data_get($meta, 'settings', []);
        if (is_string($settingsMeta)) { $decodedSettings = json_decode($settingsMeta, true); $settingsMeta = is_array($decodedSettings) ? $decodedSettings : []; }
        $query = Tenant::query()->whereKey($id);
        if (Schema::hasColumn((new Tenant())->getTable(), 'is_active')) $query->where('is_active', true);
        if (!$isSuperAdmin) {
            $extractIds = static function ($value): array {
                if (is_string($value)) { $decoded = json_decode($value, true); $value = is_array($decoded) ? $decoded : preg_split('/[,;\s]+/', trim($value), -1, PREG_SPLIT_NO_EMPTY); }
                elseif ($value instanceof \Illuminate\Support\Collection) $value = $value->all();
                elseif (is_object($value)) $value = (array) $value;
                if (!is_array($value)) return [];
                $ids = [];
                foreach ($value as $key => $item) {
                    if (is_array($item) || is_object($item)) { $item = (array) $item; $item = $item['id'] ?? $item['tenant_id'] ?? $item['shop_id'] ?? null; }
                    elseif (!is_numeric($item) && is_numeric($key) && $item) $item = $key;
                    if (is_numeric($item) && (int) $item > 0) $ids[] = (int) $item;
                }
                return array_values(array_unique($ids));
            };
            $assigned = array_values(array_unique(array_merge(
                $extractIds(data_get($meta, 'assigned_shops', [])),
                $extractIds(data_get($settingsMeta, 'assigned_shops', [])),
                $extractIds(data_get($settingsMeta, 'delivery_shops', data_get($meta, 'delivery_shops', []))),
                $user->tenant_id ? [(int) $user->tenant_id] : []
            )));
            if (!in_array($id, $assigned, true)) return null;
        }
        return $query->first();
    }
}
