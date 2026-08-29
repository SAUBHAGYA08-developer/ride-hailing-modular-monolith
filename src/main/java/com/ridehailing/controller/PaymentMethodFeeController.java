package com.ridehailing.controller;

import com.ridehailing.common.api.ApiResponse;
import com.ridehailing.payment.PaymentFeePolicy;
import com.ridehailing.payment.api.PaymentMethodFee;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Lets a driver preview a method's surcharge before Complete/Retry, without needing a ride in hand. */
@RestController
@RequestMapping("/api/v1/payment/methods/fees")
@RequiredArgsConstructor
public class PaymentMethodFeeController {

    private final PaymentFeePolicy paymentFeePolicy;

    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENT_READ')")
    public ApiResponse<List<PaymentMethodFee>> all() {
        return ApiResponse.ok(paymentFeePolicy.all());
    }
}
