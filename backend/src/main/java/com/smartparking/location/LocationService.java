package com.smartparking.location;

import com.smartparking.common.error.ApiException;
import com.smartparking.location.dto.CityDto;
import com.smartparking.location.dto.StateDetailDto;
import com.smartparking.location.dto.StateDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LocationService {

    static final int MAX_RESULTS = 50;

    private final StateRepository states;
    private final CityRepository cities;

    public List<StateDto> listStates() {
        return states.findAllWithCityCounts();
    }

    public StateDetailDto getState(String slug) {
        State state = states.findBySlug(slug).orElseThrow(() -> ApiException.notFound("State not found"));
        List<CityDto> cityDtos = cities.findByStateId(state.getId()).stream().map(CityDto::from).toList();
        return new StateDetailDto(state.getId(), state.getName(), state.getCode(), state.getSlug(),
                state.getType(), state.getCapitalName(), cityDtos);
    }

    public CityDto getCity(String stateSlug, String citySlug) {
        return cities.findBySlugs(stateSlug, citySlug).map(CityDto::from)
                .orElseThrow(() -> ApiException.notFound("City not found"));
    }

    public List<CityDto> searchCities(String query, int limit) {
        Limit max = Limit.of(Math.max(1, Math.min(limit, MAX_RESULTS)));
        List<City> found = query == null || query.isBlank()
                ? cities.findCapitals(max)
                : cities.searchByPrefix(query.trim(), max);
        return found.stream().map(CityDto::from).toList();
    }
}
