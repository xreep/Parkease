package com.smartparking.admin.locations;

import com.smartparking.common.security.AuthUser;
import com.smartparking.common.web.PageResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminLocationController {

    private final AdminLocationService service;

    @GetMapping("/states")
    public List<AdminStateDto> states() {
        return service.listStates();
    }

    @PostMapping("/states")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminStateDto createState(@AuthenticationPrincipal AuthUser admin,
                                     @Valid @RequestBody StateRequests.Create request) {
        return service.createState(admin, request);
    }

    @PatchMapping("/states/{id}")
    public AdminStateDto updateState(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id,
                                     @Valid @RequestBody StateRequests.Update request) {
        return service.updateState(admin, id, request);
    }

    @GetMapping("/cities")
    public PageResponse<AdminCityDto> cities(@RequestParam(required = false) Long stateId,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) Boolean active,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return service.listCities(stateId, q, active, page, size);
    }

    @PostMapping("/cities")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCityDto createCity(@AuthenticationPrincipal AuthUser admin,
                                   @Valid @RequestBody CityRequests.Create request) {
        return service.createCity(admin, request);
    }

    @PatchMapping("/cities/{id}")
    public AdminCityDto updateCity(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id,
                                   @Valid @RequestBody CityRequests.Update request) {
        return service.updateCity(admin, id, request);
    }
}
