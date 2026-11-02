<?php

use App\Http\Controllers\Api\DesktopDeliverymanController;
use App\Http\Controllers\Api\DeliveryCalculationController;
use Illuminate\Support\Facades\Route;

Route::prefix('desktop')->group(function () {
    Route::post('/auth/login', [DesktopDeliverymanController::class, 'login'])->middleware('throttle:8,1');

    Route::middleware('auth:sanctum')->group(function () {
        Route::get('/auth/me', [DesktopDeliverymanController::class, 'me']);
        Route::post('/auth/logout', [DesktopDeliverymanController::class, 'logout']);
        Route::get('/dashboard', [DesktopDeliverymanController::class, 'dashboard']);
        Route::patch('/status', [DesktopDeliverymanController::class, 'toggleStatus']);
        Route::get('/orders/available', [DesktopDeliverymanController::class, 'availableOrders']);
        Route::get('/orders/active', [DesktopDeliverymanController::class, 'activeOrders']);
        Route::get('/orders/completed', [DesktopDeliverymanController::class, 'completedOrders']);
        Route::post('/orders/{id}/accept', [DesktopDeliverymanController::class, 'acceptOrder']);
        Route::patch('/orders/{id}/status', [DesktopDeliverymanController::class, 'changeStatus']);
        Route::post('/orders/{id}/confirm-delivery', [DesktopDeliverymanController::class, 'confirmDelivery']);
        Route::post('/orders/{id}/payment-link', [DesktopDeliverymanController::class, 'requestDeliveryPayment']);
        Route::post('/delivery-calculator/shops/{shopId}/calculate', [DeliveryCalculationController::class, 'calculate']);
        Route::post('/shops/{id}/payment-link', [DesktopDeliverymanController::class, 'generateShopPaymentLink']);
        Route::put('/orders/{id}/details', [DesktopDeliverymanController::class, 'updateOrderDetails']);
        Route::post('/location', [DesktopDeliverymanController::class, 'updateLocation']);
        Route::get('/settings', [DesktopDeliverymanController::class, 'getSettings']);
        Route::put('/settings', [DesktopDeliverymanController::class, 'saveSettings']);
        Route::get('/shops', [DesktopDeliverymanController::class, 'getShops']);
        Route::put('/shops', [DesktopDeliverymanController::class, 'saveShops']);
        Route::get('/dialogs/{dialogId}/messages', [DesktopDeliverymanController::class, 'getDialogMessages']);
        Route::post('/dialogs/{dialogId}/messages', [DesktopDeliverymanController::class, 'sendDialogMessage']);
    });
});
