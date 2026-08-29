package com.ridehailing.controller;

import com.ridehailing.common.api.ApiResponse;
import com.ridehailing.payment.api.PaymentSummary;
import com.ridehailing.payment.dto.RetryPaymentRequest;
import com.ridehailing.payment.service.PaymentService;
import com.ridehailing.ride.service.RideLifecycleService;
import com.ridehailing.ride.service.RideQueryService;
import com.ridehailing.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Addressed under the ride, the only handle a client has, with both ownership rules delegated to the ride module. */
@RestController
@RequestMapping("/api/v1/rides/{rideId}/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final RideQueryService rideQueryService;
    private final RideLifecycleService rideLifecycleService;

    /** Reading the ride first is the ownership check: rider, assigned driver or ADMIN. */
    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENT_READ')")
    public ApiResponse<List<PaymentSummary>> ofRide(@PathVariable Long rideId) {
        rideQueryService.getById(rideId, CurrentUser.require());
        return ApiResponse.ok(paymentService.findByRide(rideId));
    }

    /** Assigned driver or ADMIN only, enforced in RideLifecycleService: the rider does not report that money arrived. */
    @PostMapping("/retry")
    @PreAuthorize("hasAuthority('PAYMENT_COLLECT')")
    public ApiResponse<PaymentSummary> retry(@PathVariable Long rideId,
                                             @Valid @RequestBody(required = false) RetryPaymentRequest request) {
        return ApiResponse.ok(rideLifecycleService.retryPayment(rideId, CurrentUser.require(),
                request == null ? null : request.paymentMethod()));
    }
}
