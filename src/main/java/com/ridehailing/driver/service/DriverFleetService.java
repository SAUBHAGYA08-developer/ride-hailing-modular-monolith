package com.ridehailing.driver.service;

import com.ridehailing.common.api.PageResponse;
import com.ridehailing.common.domain.CarType;
import com.ridehailing.driver.api.DriverCarType;
import com.ridehailing.driver.dto.FleetDriverResponse;
import com.ridehailing.driver.dto.FleetSnapshotResponse;
import com.ridehailing.driver.dto.FleetSummaryResponse;
import com.ridehailing.driver.entity.Driver;
import com.ridehailing.driver.entity.DriverStatus;
import com.ridehailing.driver.repository.DriverRepository;
import com.ridehailing.driver.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.geo.Point;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operator read side for the fleet: reconciles the two independent notions of a
 * live driver that this system maintains.
 *
 * MySQL {@code drivers.status} is the authoritative reservation state - it is
 * what booking compares and sets. Redis GEO presence is the operational one - a
 * driver is only matchable if a fresh position exists for them. Nothing keeps
 * the two in step, and nothing should: a GPS ping must not write MySQL. So they
 * drift, silently, and a driver can sit at AVAILABLE for an hour while dispatch
 * cannot see them at all. That drift is what this service measures.
 *
 * Read only, and deliberately not on any booking path - it scans the whole GEO
 * set, which dispatch must never do.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DriverFleetService {

    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverLocationService driverLocationService;

    /**
     * @param status optional filter on the MySQL reservation state; the summary
     *               always covers the whole fleet regardless of it, because a
     *               filtered total would answer a different question than the
     *               one the caller asked.
     */
    @Transactional(readOnly = true)
    public FleetSnapshotResponse snapshot(DriverStatus status, Pageable pageable) {
        // Redis is read exactly once and the same live set feeds the summary and
        // every row, so the totals can never contradict the flags beside them.
        Map<Long, Point> livePositions = driverLocationService.livePositions();
        Set<Long> liveDriverIds = livePositions.keySet();

        Page<Driver> page = status == null
                ? driverRepository.findAllByOrderByIdAsc(pageable)
                : driverRepository.findByStatusOrderByIdAsc(status, pageable);

        // Bookable is a two-store fact: AVAILABLE with an active vehicle in
        // MySQL, and present in Redis. MySQL answers the first half and the
        // intersection below applies the half only Redis knows.
        Set<Long> bookableIds = new HashSet<>(driverRepository.findAvailableDriverIdsWithActiveVehicle());
        bookableIds.retainAll(liveDriverIds);

        Map<Long, List<CarType>> carTypes = activeCarTypes(page.getContent());

        return new FleetSnapshotResponse(
                summary(liveDriverIds, bookableIds.size()),
                PageResponse.from(page, driver -> toRow(driver, livePositions, bookableIds, carTypes)));
    }

    private FleetSummaryResponse summary(Set<Long> liveDriverIds, long bookable) {
        // Seeded with every constant so a status with no drivers renders as 0
        // instead of vanishing; LinkedHashMap keeps enum order, which makes the
        // JSON stable enough for a dashboard to lay out against.
        Map<DriverStatus, Long> byStatus = new LinkedHashMap<>();
        for (DriverStatus status : DriverStatus.values()) {
            byStatus.put(status, 0L);
        }
        driverRepository.countByStatus().forEach(row -> byStatus.put(row.status(), row.count()));

        // status is NOT NULL, so the grouped counts already sum to the fleet: a
        // separate count(*) would only add a query and a chance to disagree.
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long onDuty = total - byStatus.get(DriverStatus.OFFLINE);

        // Asked against the live ids rather than derived from their size: the GEO
        // set can legitimately still hold a driver MySQL has since taken OFFLINE,
        // and counting those as on duty would understate the ghosts.
        long onDutyAndLive = liveDriverIds.isEmpty() ? 0L : driverRepository.countOnDutyIn(liveDriverIds);
        long ghosts = onDuty - onDutyAndLive;

        if (ghosts > 0) {
            log.info("Fleet view: {} of {} on-duty drivers have no live position within {}s",
                    ghosts, onDuty, driverLocationService.locationTtlSeconds());
        }

        return new FleetSummaryResponse(total, byStatus, liveDriverIds.size(), ghosts, bookable,
                driverLocationService.locationTtlSeconds());
    }

    /**
     * One query for the whole page, regrouped here. Joining vehicles into the
     * listing query instead would break the page itself: a driver with two
     * active vehicles would occupy two rows and a driver with none would drop out
     * of a listing whose entire job is to account for every driver.
     */
    private Map<Long, List<CarType>> activeCarTypes(List<Driver> drivers) {
        if (drivers.isEmpty()) {
            return Map.of();
        }
        List<Long> driverIds = drivers.stream().map(Driver::getId).toList();

        Map<Long, List<CarType>> byDriver = new HashMap<>();
        for (DriverCarType row : vehicleRepository.findActiveCarTypes(driverIds)) {
            byDriver.computeIfAbsent(row.driverId(), id -> new ArrayList<>()).add(row.carType());
        }
        return byDriver;
    }

    /**
     * Phone, e-mail and licence number are dropped on purpose: a live-driver
     * headcount needs no personal data, so the endpoint never carries any.
     */
    private FleetDriverResponse toRow(Driver driver,
                                      Map<Long, Point> livePositions,
                                      Set<Long> bookableIds,
                                      Map<Long, List<CarType>> carTypes) {
        // Redis orders a Point as (x = longitude, y = latitude), the reverse of how they are quoted elsewhere.
        Point position = livePositions.get(driver.getId());
        return new FleetDriverResponse(
                driver.getId(),
                driver.getFullName(),
                driver.getStatus(),
                carTypes.getOrDefault(driver.getId(), List.of()),
                driver.getRating(),
                driver.getTotalRides(),
                position != null,
                bookableIds.contains(driver.getId()),
                position == null ? null : BigDecimal.valueOf(position.getY()).setScale(6, RoundingMode.HALF_UP),
                position == null ? null : BigDecimal.valueOf(position.getX()).setScale(6, RoundingMode.HALF_UP),
                driver.getLastLocationAt());
    }
}
