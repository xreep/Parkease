package com.smartparking.admin.locations;

import com.smartparking.admin.audit.AdminAuditService;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.util.SqlStates;
import com.smartparking.common.web.PageResponse;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.location.City;
import com.smartparking.location.CityRepository;
import com.smartparking.location.State;
import com.smartparking.location.StateRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin management of states and cities. A city's slug is derived from its name and is unique within its state; a
 * state's slug is unique across the country. Cities are deactivated, never deleted, so listings keep their city.
 */
@Service
@RequiredArgsConstructor
public class AdminLocationService {

    private final StateRepository states;
    private final CityRepository cities;
    private final ParkingListingRepository listings;
    private final AdminAuditService audit;

    // ---- states -------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AdminStateDto> listStates() {
        return states.findAllWithTotalCityCounts().stream()
                .map(t -> stateDto(t.getState(), t.getCityCount())).toList();
    }

    @Transactional
    public AdminStateDto createState(AuthUser admin, StateRequests.Create request) {
        String name = cleanName(request.name());
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        String slug = slugOf(name);
        if (states.existsBySlug(slug) || states.existsByNameIgnoreCase(name)) {
            throw slugTaken(slug);
        }
        if (states.existsByCodeIgnoreCase(code)) {
            throw ApiException.conflict("CODE_TAKEN", "A state or territory with code " + code + " already exists");
        }
        State state = new State();
        state.setName(name);
        state.setCode(code);
        state.setSlug(slug);
        state.setType(request.type());
        state.setCapitalName(request.capitalName().trim());
        saveOrConflict(() -> states.saveAndFlush(state), slug);
        audit.record(admin, "STATE_CREATED", "STATE", state.getId(), "Created " + name + " (" + code + ")");
        return stateDto(state, 0);
    }

    @Transactional
    public AdminStateDto updateState(AuthUser admin, Long id, StateRequests.Update request) {
        State state = states.findById(id).orElseThrow(() -> ApiException.notFound("State not found"));
        List<String> changes = new ArrayList<>();
        if (request.name() != null && !cleanName(request.name()).equals(state.getName())) {
            String name = cleanName(request.name());
            String slug = slugOf(name);
            if (states.existsBySlugAndIdNot(slug, id) || states.existsByNameIgnoreCaseAndIdNot(name, id)) {
                throw slugTaken(slug);
            }
            changes.add("name: " + state.getName() + " -> " + name);
            state.setName(name); // the slug stays as it was: public URLs must not change when a name is edited
        }
        if (request.type() != null && request.type() != state.getType()) {
            changes.add("type: " + state.getType() + " -> " + request.type());
            state.setType(request.type());
        }
        if (request.capitalName() != null && !request.capitalName().trim().equals(state.getCapitalName())) {
            changes.add("capitalName: " + state.getCapitalName() + " -> " + request.capitalName().trim());
            state.setCapitalName(request.capitalName().trim());
        }
        if (!changes.isEmpty()) {
            states.saveAndFlush(state);
            audit.record(admin, "STATE_UPDATED", "STATE", id, String.join("; ", changes));
        }
        return stateDto(state, cities.countByStateId(id));
    }

    // ---- cities -------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<AdminCityDto> listCities(Long stateId, String q, Boolean active, int page, int size) {
        int mode = active == null ? 0 : active ? 1 : 2;
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<City> result = cities.adminSearch(stateId == null ? 0L : stateId, mode, likePattern(q), pageable);
        List<Long> ids = result.getContent().stream().map(City::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : listings.countByCities(ids)) {
                counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            }
        }
        return PageResponse.from(result.map(c -> cityDto(c, counts.getOrDefault(c.getId(), 0L))));
    }

    @Transactional
    public AdminCityDto createCity(AuthUser admin, CityRequests.Create request) {
        State state = states.findById(request.stateId()).orElseThrow(() -> ApiException.notFound("State not found"));
        String name = cleanName(request.name());
        String slug = slugOf(name);
        if (cities.existsByStateIdAndSlug(state.getId(), slug)) {
            throw slugTaken(slug);
        }
        City city = new City();
        city.setState(state);
        city.setName(name);
        city.setSlug(slug);
        city.setLat(request.lat());
        city.setLng(request.lng());
        city.setCapital(Boolean.TRUE.equals(request.capital()));
        city.setTier(request.tier() == null ? 3 : request.tier().shortValue());
        city.setActive(request.active() == null || request.active());
        saveOrConflict(() -> cities.saveAndFlush(city), slug);
        audit.record(admin, "CITY_CREATED", "CITY", city.getId(),
                "Created " + name + " in " + state.getName() + " (tier " + city.getTier() + ")");
        return cityDto(city, 0);
    }

    @Transactional
    public AdminCityDto updateCity(AuthUser admin, Long id, CityRequests.Update request) {
        City city = cities.findById(id).orElseThrow(() -> ApiException.notFound("City not found"));
        List<String> changes = new ArrayList<>();
        if (request.name() != null && !cleanName(request.name()).equals(city.getName())) {
            String name = cleanName(request.name());
            String slug = slugOf(name);
            if (cities.existsByStateIdAndSlugAndIdNot(city.getState().getId(), slug, id)) {
                throw slugTaken(slug);
            }
            changes.add("name: " + city.getName() + " -> " + name);
            city.setName(name); // the slug stays as it was: public URLs must not change when a name is edited
        }
        if (request.lat() != null && request.lat() != city.getLat()) {
            changes.add("lat: " + city.getLat() + " -> " + request.lat());
            city.setLat(request.lat());
        }
        if (request.lng() != null && request.lng() != city.getLng()) {
            changes.add("lng: " + city.getLng() + " -> " + request.lng());
            city.setLng(request.lng());
        }
        if (request.capital() != null && request.capital() != city.isCapital()) {
            changes.add("capital: " + city.isCapital() + " -> " + request.capital());
            city.setCapital(request.capital());
        }
        if (request.tier() != null && request.tier() != city.getTier()) {
            changes.add("tier: " + city.getTier() + " -> " + request.tier());
            city.setTier(request.tier().shortValue());
        }
        if (request.active() != null && request.active() != city.isActive()) {
            changes.add("active: " + city.isActive() + " -> " + request.active());
            city.setActive(request.active());
        }
        if (!changes.isEmpty()) {
            cities.saveAndFlush(city);
            audit.record(admin, "CITY_UPDATED", "CITY", id, String.join("; ", changes));
        }
        return cityDto(city, listings.countByCityId(id));
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private AdminStateDto stateDto(State s, long citiesCount) {
        return new AdminStateDto(s.getId(), s.getName(), s.getCode(), s.getSlug(), s.getType(), s.getCapitalName(),
                citiesCount);
    }

    private AdminCityDto cityDto(City c, long listingsCount) {
        return new AdminCityDto(c.getId(), c.getState().getId(), c.getState().getName(), c.getName(), c.getSlug(),
                c.getLat(), c.getLng(), c.isCapital(), c.getTier(), c.isActive(), listingsCount);
    }

    private static String cleanName(String raw) {
        String name = Objects.requireNonNull(raw).trim().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 100) {
            throw ApiException.badRequest("VALIDATION_FAILED", "The name must be 2 to 100 characters");
        }
        return name;
    }

    private static String slugOf(String name) {
        String slug = Slugs.of(name);
        if (slug.isEmpty()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "The name must contain letters or digits");
        }
        return slug;
    }

    /**
     * Runs the insert; a unique-constraint violation (two admins creating the same place at once get past the
     * existence checks together) becomes the same 409 the checks would have given.
     */
    private static void saveOrConflict(Runnable save, String slug) {
        try {
            save.run();
        } catch (DataIntegrityViolationException e) {
            if (!SqlStates.UNIQUE_VIOLATION.equals(SqlStates.of(e))) {
                throw e;
            }
            String constraint = null;
            for (Throwable t = e; t != null && constraint == null; t = t.getCause()) {
                if (t instanceof ConstraintViolationException cve) {
                    constraint = cve.getConstraintName();
                }
            }
            if (constraint != null && "states_code_key".equals(constraint)) {
                throw ApiException.conflict("CODE_TAKEN", "A state or territory with this code already exists");
            }
            throw slugTaken(slug);
        }
    }

    private static ApiException slugTaken(String slug) {
        return ApiException.conflict("SLUG_TAKEN", "The name is already in use (it maps to '" + slug + "')");
    }

    private static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        return "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                + "%";
    }
}
