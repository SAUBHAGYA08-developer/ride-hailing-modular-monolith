package com.ridehailing.controller;

import com.ridehailing.common.api.ApiResponse;
import com.ridehailing.driver.dto.CreateVehicleRequest;
import com.ridehailing.driver.dto.VehicleResponse;
import com.ridehailing.driver.service.DriverService;
import com.ridehailing.driver.service.VehicleService;
import com.ridehailing.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/drivers/{driverId}/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;
    private final DriverService driverService;

    @PostMapping
    @PreAuthorize("hasAuthority('VEHICLE_CREATE')")
    public ResponseEntity<ApiResponse<VehicleResponse>> register(@PathVariable Long driverId,
                                                                 @Valid @RequestBody CreateVehicleRequest request) {
        driverService.requireOwnership(CurrentUser.require(), driverId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(vehicleService.register(driverId, request)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VEHICLE_READ')")
    public ApiResponse<List<VehicleResponse>> findByDriver(@PathVariable Long driverId) {
        driverService.requireOwnership(CurrentUser.require(), driverId);
        return ApiResponse.ok(vehicleService.findByDriver(driverId));
    }
}
