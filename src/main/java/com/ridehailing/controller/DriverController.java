package com.ridehailing.controller;

import com.ridehailing.common.api.ApiResponse;
import com.ridehailing.driver.dto.CreateDriverRequest;
import com.ridehailing.driver.dto.DriverResponse;
import com.ridehailing.driver.dto.FleetSnapshotResponse;
import com.ridehailing.driver.dto.UpdateDriverStatusRequest;
import com.ridehailing.driver.dto.UpdateLocationRequest;
import com.ridehailing.driver.entity.DriverStatus;
import com.ridehailing.driver.service.DriverFleetService;
import com.ridehailing.driver.service.DriverLocationService;
import com.ridehailing.driver.service.DriverService;
import com.ridehailing.ratelimit.RateLimitPolicy;
import com.ridehailing.ratelimit.RateLimited;
import com.ridehailing.ride.dto.RideOfferSummary;
import com.ridehailing.ride.service.RideOfferService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/drivers")
@RequiredArgsConstructor
public class DriverController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DriverService driverService;
    private final DriverLocationService driverLocationService;
    private final DriverFleetService driverFleetService;
    private final RideOfferService rideOfferService;

    /** Public self onboarding. The DRIVER role is assigned by the server. */
    @PostMapping
    public ResponseEntity<ApiResponse<DriverResponse>> register(@Valid @RequestBody CreateDriverRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(driverService.register(request)));
    }

    /**
     * The fleet view: how many drivers exist, how many are actually reporting a
     * live position, and how many could take a ride this second. The gap between
     * the first two is the answer an operator cannot get anywhere else.
     *
     * Guarded by DRIVER_FLEET_READ, not DRIVER_READ. Every driver already holds
     * DRIVER_READ for their own profile, and reusing it here would turn this into
     * a fleet-wide directory any driver could enumerate.
     *
     * Declared above the /{driverId} mapping and matched on a literal segment, so
     * Spring's pattern comparator prefers it over the template: "fleet" can never
     * be swallowed by the id route and parsed as a driver id.
     */
    @GetMapping("/fleet")
    @PreAuthorize("hasAuthority('DRIVER_FLEET_READ')")
    public ApiResponse<FleetSnapshotResponse> fleet(@RequestParam(required = false) DriverStatus status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        // Size is clamped rather than trusted: this endpoint scans the whole GEO
        // set already, and an unbounded page would let one caller pull the fleet.
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return ApiResponse.ok(driverFleetService.snapshot(status, pageable));
    }

    @GetMapping("/{driverId}")
    @PreAuthorize("hasAuthority('DRIVER_READ')")
    public ApiResponse<DriverResponse> getById(@PathVariable Long driverId) {
        driverService.requireOwnership(CurrentUser.require(), driverId);
        return ApiResponse.ok(driverService.getById(driverId));
    }

    /**
     * What a driver's app polls to discover an incoming offer: a REQUESTED
     * ride has no driverId yet, so it isn't reachable through GET /rides/{id}
     * for anyone but the rider. 204 rather than a null data field: shared.js's
     * RH.api resolves an empty body to null, but ApiResponse omits a null data
     * field entirely (@JsonInclude(NON_NULL)), which would otherwise resolve
     * to the truthy envelope itself.
     */
    @GetMapping("/{driverId}/offers/pending")
    @PreAuthorize("hasAuthority('RIDE_OFFER_RESPOND')")
    public ResponseEntity<ApiResponse<RideOfferSummary>> pendingOffer(@PathVariable Long driverId) {
        driverService.requireOwnership(CurrentUser.require(), driverId);
        return rideOfferService.findPendingOfferSummary(driverId)
                .map(summary -> ResponseEntity.ok(ApiResponse.ok(summary)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Position updates go to Redis only. The path driverId is verified against
     * the authenticated identity, so a driver can never move another driver.
     */
    @PutMapping("/{driverId}/location")
    @PreAuthorize("hasAuthority('DRIVER_LOCATION_UPDATE')")
    @RateLimited(RateLimitPolicy.DRIVER_LOCATION)
    public ResponseEntity<Void> updateLocation(@PathVariable Long driverId,
                                               @Valid @RequestBody UpdateLocationRequest request) {
        AuthPrincipal principal = CurrentUser.require();
        driverService.requireOwnership(principal, driverId);
        driverLocationService.updateLocation(driverId, request.latitude(), request.longitude());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{driverId}/status")
    @PreAuthorize("hasAuthority('DRIVER_STATUS_UPDATE')")
    public ApiResponse<DriverResponse> updateStatus(@PathVariable Long driverId,
                                                    @Valid @RequestBody UpdateDriverStatusRequest request) {
        driverService.requireOwnership(CurrentUser.require(), driverId);
        return ApiResponse.ok(driverService.updateStatus(driverId, request.status()));
    }
}
