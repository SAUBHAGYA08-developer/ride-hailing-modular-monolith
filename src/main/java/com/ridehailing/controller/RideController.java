package com.ridehailing.controller;

import com.ridehailing.common.api.ApiResponse;
import com.ridehailing.common.api.PageResponse;
import com.ridehailing.common.web.RequestContext;
import com.ridehailing.ratelimit.RateLimitPolicy;
import com.ridehailing.ratelimit.RateLimited;
import com.ridehailing.ride.dto.CancelRideRequest;
import com.ridehailing.ride.dto.CreateRideRequest;
import com.ridehailing.ride.dto.RideResponse;
import com.ridehailing.ride.service.RideBookingService;
import com.ridehailing.ride.service.RideLifecycleService;
import com.ridehailing.ride.service.RideQueryService;
import com.ridehailing.security.AuthPrincipal;
import com.ridehailing.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RideController {

    private static final int MAX_PAGE_SIZE = 100;

    private final RideBookingService rideBookingService;
    private final RideLifecycleService rideLifecycleService;
    private final RideQueryService rideQueryService;

    /** The rider is the authenticated principal; the request body carries no userId. */
    @PostMapping("/rides")
    @PreAuthorize("hasAuthority('RIDE_CREATE')")
    @RateLimited(RateLimitPolicy.RIDE_CREATE)
    public ResponseEntity<ApiResponse<RideResponse>> book(
            @Valid @RequestBody CreateRideRequest request,
            @RequestHeader(value = RequestContext.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {

        AuthPrincipal principal = CurrentUser.require();
        RideResponse ride = rideBookingService.book(principal.userId(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(ride));
    }

    @GetMapping("/rides/{rideId}")
    @PreAuthorize("hasAuthority('RIDE_READ')")
    public ApiResponse<RideResponse> getById(@PathVariable Long rideId) {
        return ApiResponse.ok(rideQueryService.getById(rideId, CurrentUser.require()));
    }

    @PostMapping("/rides/{rideId}/start")
    @PreAuthorize("hasAuthority('RIDE_START')")
    public ApiResponse<RideResponse> start(@PathVariable Long rideId) {
        return ApiResponse.ok(rideLifecycleService.start(rideId, CurrentUser.require()));
    }

    @PostMapping("/rides/{rideId}/complete")
    @PreAuthorize("hasAuthority('RIDE_COMPLETE')")
    public ApiResponse<RideResponse> complete(@PathVariable Long rideId) {
        return ApiResponse.ok(rideLifecycleService.complete(rideId, CurrentUser.require()));
    }

    @PostMapping("/rides/{rideId}/cancel")
    @PreAuthorize("hasAuthority('RIDE_CANCEL')")
    public ApiResponse<RideResponse> cancel(@PathVariable Long rideId,
                                            @Valid @RequestBody(required = false) CancelRideRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(rideLifecycleService.cancel(rideId, CurrentUser.require(), reason));
    }

    @GetMapping("/users/{userId}/rides")
    @PreAuthorize("hasAuthority('RIDE_READ')")
    public ApiResponse<PageResponse<RideResponse>> ridesOfUser(@PathVariable Long userId,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(rideQueryService.findByUser(userId, CurrentUser.require(), pageable(page, size)));
    }

    @GetMapping("/drivers/{driverId}/rides")
    @PreAuthorize("hasAuthority('RIDE_READ')")
    public ApiResponse<PageResponse<RideResponse>> ridesOfDriver(@PathVariable Long driverId,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(rideQueryService.findByDriver(driverId, CurrentUser.require(), pageable(page, size)));
    }

    private PageRequest pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
