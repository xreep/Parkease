package com.smartparking.vehicle;

import com.smartparking.common.error.ApiException;
import com.smartparking.user.UserRepository;
import com.smartparking.vehicle.dto.VehicleDto;
import com.smartparking.vehicle.dto.VehicleRequest;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class VehicleService {

    static final int MAX_VEHICLES = 10;

    private final VehicleRepository vehicles;
    private final UserRepository users;

    @Transactional(readOnly = true)
    public List<VehicleDto> list(Long userId) {
        return vehicles.findByUserIdOrderByIsDefaultDescCreatedAtAsc(userId).stream().map(this::toDto).toList();
    }

    public VehicleDto create(Long userId, VehicleRequest request) {
        String plate = requireValidPlate(request.plateNumber());
        if (vehicles.countByUserId(userId) >= MAX_VEHICLES) {
            throw ApiException.conflict("VEHICLE_LIMIT", "You can save at most " + MAX_VEHICLES + " vehicles");
        }
        requirePlateFree(userId, plate);
        boolean first = vehicles.countByUserId(userId) == 0;
        boolean makeDefault = first || Boolean.TRUE.equals(request.isDefault());
        if (makeDefault) {
            clearDefaults(userId);
        }
        Vehicle vehicle = new Vehicle();
        vehicle.setUser(users.getReferenceById(userId));
        apply(vehicle, request, plate);
        vehicle.setDefault(makeDefault);
        return toDto(saveOrPlateTaken(vehicle));
    }

    public VehicleDto update(Long userId, Long id, VehicleRequest request) {
        Vehicle vehicle = require(userId, id);
        String plate = requireValidPlate(request.plateNumber());
        if (!plate.equals(vehicle.getPlateNumber())) {
            requirePlateFree(userId, plate);
        }
        // Only an explicit true moves the default; a user always keeps exactly one default vehicle.
        if (Boolean.TRUE.equals(request.isDefault()) && !vehicle.isDefault()) {
            clearDefaults(userId);
            vehicle.setDefault(true);
        }
        apply(vehicle, request, plate);
        return toDto(saveOrPlateTaken(vehicle));
    }

    public void delete(Long userId, Long id) {
        Vehicle vehicle = require(userId, id);
        boolean wasDefault = vehicle.isDefault();
        // Bookings keep their plate snapshot; the FK sets bookings.vehicle_id to null.
        vehicles.delete(vehicle);
        vehicles.flush();
        if (wasDefault) {
            vehicles.findByUserIdOrderByIsDefaultDescCreatedAtAsc(userId).stream()
                    .min(Comparator.comparing(Vehicle::getCreatedAt).thenComparing(Vehicle::getId))
                    .ifPresent(oldest -> oldest.setDefault(true));
        }
    }

    private void clearDefaults(Long userId) {
        vehicles.findByUserIdOrderByIsDefaultDescCreatedAtAsc(userId).stream()
                .filter(Vehicle::isDefault)
                .forEach(v -> v.setDefault(false));
        vehicles.flush();
    }

    private void apply(Vehicle vehicle, VehicleRequest request, String plate) {
        vehicle.setType(request.type());
        vehicle.setPlateNumber(plate);
        String makeModel = request.makeModel() == null ? "" : request.makeModel().trim();
        vehicle.setMakeModel(makeModel.isEmpty() ? null : makeModel);
    }

    private Vehicle saveOrPlateTaken(Vehicle vehicle) {
        try {
            return vehicles.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException e) {
            throw plateTaken();
        }
    }

    private String requireValidPlate(String raw) {
        String plate = PlateNumbers.normalize(raw);
        if (!PlateNumbers.isValid(plate)) {
            throw ApiException.badRequest("INVALID_PLATE", "Enter a valid Indian number plate, e.g. MH12AB1234");
        }
        return plate;
    }

    private void requirePlateFree(Long userId, String plate) {
        if (vehicles.existsByUserIdAndPlateNumber(userId, plate)) {
            throw plateTaken();
        }
    }

    private ApiException plateTaken() {
        return ApiException.conflict("PLATE_TAKEN", "You have already saved a vehicle with this number plate");
    }

    private Vehicle require(Long userId, Long id) {
        return vehicles.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Vehicle not found"));
    }

    private VehicleDto toDto(Vehicle v) {
        return new VehicleDto(v.getId(), v.getType(), v.getPlateNumber(), v.getMakeModel(), v.isDefault());
    }
}
