package com.ridehailing.controller;

import com.ridehailing.common.api.ApiResponse;
import com.ridehailing.coupon.dto.CouponResponse;
import com.ridehailing.coupon.dto.CouponValidationResponse;
import com.ridehailing.coupon.dto.CreateCouponRequest;
import com.ridehailing.coupon.dto.ValidateCouponRequest;
import com.ridehailing.coupon.service.CouponService;
import com.ridehailing.ratelimit.RateLimitPolicy;
import com.ridehailing.ratelimit.RateLimited;
import com.ridehailing.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @PostMapping
    @PreAuthorize("hasAuthority('COUPON_CREATE')")
    public ResponseEntity<ApiResponse<CouponResponse>> create(@Valid @RequestBody CreateCouponRequest request) {
        CouponResponse created = couponService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /**
     * Soft delete: the coupon moves to INACTIVE and stays readable, because
     * redemptions reference it and past rides must remain explainable.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('COUPON_DELETE')")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        couponService.deactivate(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /** The rider is the authenticated principal; the body carries only the fare. */
    @PostMapping("/{code}/validate")
    @PreAuthorize("hasAuthority('COUPON_VALIDATE')")
    @RateLimited(RateLimitPolicy.COUPON_VALIDATE)
    public ApiResponse<CouponValidationResponse> validate(@PathVariable String code,
                                                          @Valid @RequestBody ValidateCouponRequest request) {
        Long userId = CurrentUser.require().userId();
        return ApiResponse.ok(couponService.validate(code, request.fareAmount(), userId));
    }
}
