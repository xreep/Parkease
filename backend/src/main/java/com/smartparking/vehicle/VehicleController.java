package com.smartparking.vehicle;

import com.smartparking.common.security.AuthUser;
import com.smartparking.common.security.Roles;
import com.smartparking.vehicle.dto.VehicleDto;
import com.smartparking.vehicle.dto.VehicleRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService service;

    @GetMapping
    public List<VehicleDto> list(@AuthenticationPrincipal AuthUser principal) {
        Roles.requireDriver(principal);
        return service.list(principal.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VehicleDto create(@AuthenticationPrincipal AuthUser principal, @Valid @RequestBody VehicleRequest request) {
        Roles.requireDriver(principal);
        return service.create(principal.id(), request);
    }

    @PutMapping("/{id}")
    public VehicleDto update(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id,
                             @Valid @RequestBody VehicleRequest request) {
        Roles.requireDriver(principal);
        return service.update(principal.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        Roles.requireDriver(principal);
        service.delete(principal.id(), id);
    }
}
