package com.smartparking.location;

import com.smartparking.location.dto.CityDto;
import com.smartparking.location.dto.StateDetailDto;
import com.smartparking.location.dto.StateDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locations;

    @GetMapping("/states")
    public List<StateDto> states() {
        return locations.listStates();
    }

    @GetMapping("/states/{stateSlug}")
    public StateDetailDto state(@PathVariable String stateSlug) {
        return locations.getState(stateSlug);
    }

    @GetMapping("/states/{stateSlug}/cities/{citySlug}")
    public CityDto city(@PathVariable String stateSlug, @PathVariable String citySlug) {
        return locations.getCity(stateSlug, citySlug);
    }

    @GetMapping("/cities")
    public List<CityDto> searchCities(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "20") int limit) {
        return locations.searchCities(q, limit);
    }
}
