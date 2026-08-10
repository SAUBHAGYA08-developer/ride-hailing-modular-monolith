package com.ridehailing.driver.service;

import com.ridehailing.audit.AuditActions;
import com.ridehailing.audit.AuditEntities;
import com.ridehailing.audit.service.AuditService;
import com.ridehailing.common.error.ErrorCode;
import com.ridehailing.common.exception.BusinessException;
import com.ridehailing.driver.dto.CreateVehicleRequest;
import com.ridehailing.driver.dto.VehicleResponse;
import com.ridehailing.driver.entity.Vehicle;
import com.ridehailing.driver.repository.DriverRepository;
import com.ridehailing.driver.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final AuditService auditService;

    /**
     * A driver serves rides with exactly one vehicle, so registering a new one
     * retires the previous. Both writes share a transaction: a driver must
     * never be left with two active vehicles or with none.
     */
    @Transactional
    public VehicleResponse register(Long driverId, CreateVehicleRequest request) {
        if (!driverRepository.existsById(driverId)) {
            throw new BusinessException(ErrorCode.DRIVER_NOT_FOUND, "Driver " + driverId + " does not exist");
        }
        String registration = request.registrationNumber().trim().toUpperCase();
        if (vehicleRepository.existsByRegistrationNumber(registration)) {
            throw new BusinessException(ErrorCode.VEHICLE_ALREADY_REGISTERED,
                    "Vehicle " + registration + " is already registered");
        }

        vehicleRepository.findByDriverIdAndActiveTrue(driverId).forEach(existing -> existing.setActive(false));
        vehicleRepository.flush();

        Vehicle vehicle = new Vehicle();
        vehicle.setDriverId(driverId);
        vehicle.setCarType(request.carType());
        vehicle.setRegistrationNumber(registration);
        vehicle.setMake(request.make());
        vehicle.setModel(request.model());
        vehicle.setColor(request.color());
        vehicle.setActive(true);

        Vehicle saved = vehicleRepository.save(vehicle);
        auditService.record(AuditEntities.VEHICLE, saved.getId(), AuditActions.VEHICLE_REGISTERED, null,
                Map.of("driverId", driverId, "carType", saved.getCarType().name(),
                        "registrationNumber", saved.getRegistrationNumber()));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> findByDriver(Long driverId) {
        return vehicleRepository.findByDriverId(driverId).stream().map(this::toResponse).toList();
    }

    private VehicleResponse toResponse(Vehicle vehicle) {
        return new VehicleResponse(vehicle.getId(), vehicle.getDriverId(), vehicle.getCarType(),
                vehicle.getRegistrationNumber(), vehicle.getMake(), vehicle.getModel(), vehicle.getColor(),
                vehicle.isActive());
    }
}
