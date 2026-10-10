package com.smartparking.settings;

import com.smartparking.common.error.ApiException;
import com.smartparking.common.security.AuthUser;
import com.smartparking.common.security.Roles;
import com.smartparking.location.City;
import com.smartparking.location.CityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The advisory price range the owner wizard shows for a city. Never a hard limit. */
@RestController
@RequestMapping("/api/v1/pricing-guidelines")
@RequiredArgsConstructor
public class PricingGuidelineController {

    private final CityRepository cities;
    private final PlatformSettings settings;

    @GetMapping
    @Transactional(readOnly = true)
    public CityPriceGuidelineDto get(@AuthenticationPrincipal AuthUser principal, @RequestParam Long cityId) {
        Roles.requireOwnerOrAdmin(principal);
        City city = cities.findById(cityId).orElseThrow(() -> ApiException.notFound("City not found"));
        PlatformSettings.PriceGuideline g = settings.priceGuideline(city.getTier());
        return new CityPriceGuidelineDto(g.tier(), g.minHourly(), g.maxHourly());
    }
}
