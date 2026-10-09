package com.smartparking.search;

import com.smartparking.availability.AvailabilityBlock;
import com.smartparking.availability.AvailabilityBlockRepository;
import com.smartparking.availability.AvailabilityEvaluator;
import com.smartparking.availability.AvailabilityEvaluator.ListingAvailabilityInput;
import com.smartparking.availability.AvailabilityRule;
import com.smartparking.availability.AvailabilityRuleRepository;
import com.smartparking.booking.BookingRepository;
import com.smartparking.listing.Amenity;
import com.smartparking.listing.ListingMapper;
import com.smartparking.listing.ListingPhoto;
import com.smartparking.listing.ListingPhotoRepository;
import com.smartparking.listing.ParkingListing;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.pricing.PricingService;
import com.smartparking.pricing.Quote;
import com.smartparking.pricing.QuoteDto;
import com.smartparking.pricing.TimeWindow;
import com.smartparking.search.SearchCandidateRepository.Candidate;
import com.smartparking.slot.ParkingSlot;
import com.smartparking.slot.ParkingSlotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Candidate query (native SQL) -> batched loading of children -> availability and quotes -> sort -> page.
 * Queries issued are constant in number regardless of the candidate count.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private final SearchCandidateRepository candidates;
    private final ParkingListingRepository listings;
    private final ListingPhotoRepository photos;
    private final ParkingSlotRepository slots;
    private final AvailabilityRuleRepository rules;
    private final AvailabilityBlockRepository blocks;
    private final AvailabilityEvaluator evaluator;
    private final PricingService pricing;
    private final ListingMapper mapper;
    private final BookingRepository bookings;
    private final Clock clock;

    /** A candidate that survived availability evaluation. */
    private record Match(ParkingListing listing, double distanceKm, int totalSlots, Integer freeSlots, Quote quote) {
    }

    @Transactional(readOnly = true)
    public SearchResponse search(SearchCriteria c) {
        List<Candidate> found = candidates.findCandidates(c);
        List<Match> matches = found.isEmpty() ? List.of() : evaluate(c, found);

        List<Match> sorted = new ArrayList<>(matches);
        sorted.sort(comparator(c));

        int total = sorted.size();
        int from = (int) Math.min((long) c.page() * c.size(), total);
        int to = Math.min(from + c.size(), total);
        List<Match> pageItems = sorted.subList(from, to);

        List<SearchResultDto> content = toDtos(pageItems);
        int totalPages = (int) ((total + c.size() - 1L) / c.size());
        SearchResponse.Window window = c.window() == null ? null
                : new SearchResponse.Window(c.window().start(), c.window().end());
        return new SearchResponse(content, c.page(), c.size(), total, totalPages,
                new SearchResponse.Center(c.lat(), c.lng()), c.radiusKm(), window);
    }

    private List<Match> evaluate(SearchCriteria c, List<Candidate> found) {
        List<Long> ids = found.stream().map(Candidate::id).toList();
        Map<Long, Double> distance = new HashMap<>();
        found.forEach(f -> distance.put(f.id(), f.distanceKm()));

        // Listings first: later queries' lazy references resolve to these already-loaded instances.
        Map<Long, ParkingListing> byId = new HashMap<>();
        listings.findAllWithCityAndStateByIdIn(ids).forEach(l -> byId.put(l.getId(), l));
        Map<Long, List<ParkingSlot>> slotsByListing = new HashMap<>();
        slots.findByListingIdInAndActiveTrue(ids)
                .forEach(s -> slotsByListing.computeIfAbsent(s.getListing().getId(), k -> new ArrayList<>()).add(s));

        TimeWindow window = c.window();
        Map<Long, List<AvailabilityRule>> rulesByListing = new HashMap<>();
        Map<Long, List<AvailabilityBlock>> blocksByListing = new HashMap<>();
        Set<Long> bookedSlotIds = new HashSet<>();
        if (window != null) {
            List<Long> needRules = ids.stream().filter(id -> byId.containsKey(id) && !byId.get(id).isOpen24x7()).toList();
            if (!needRules.isEmpty()) {
                rules.findByListingIdIn(needRules)
                        .forEach(r -> rulesByListing.computeIfAbsent(r.getListing().getId(), k -> new ArrayList<>()).add(r));
            }
            blocks.findOverlapping(ids, window.start(), window.end())
                    .forEach(b -> blocksByListing.computeIfAbsent(b.getListing().getId(), k -> new ArrayList<>()).add(b));
            // Slot ids are globally unique, so one set serves every candidate listing.
            bookedSlotIds.addAll(
                    bookings.findLiveOverlappingSlotIds(ids, window.start(), window.end(), clock.instant()));
        }

        List<Match> out = new ArrayList<>();
        for (Candidate cand : found) {
            ParkingListing l = byId.get(cand.id());
            if (l == null) {
                continue;
            }
            List<ParkingSlot> listingSlots = slotsByListing.getOrDefault(l.getId(), List.of());
            if (window == null) {
                int total = (int) listingSlots.stream()
                        .filter(s -> c.vehicleType() == null || s.getVehicleType() == c.vehicleType()).count();
                out.add(new Match(l, cand.distanceKm(), total, null, null));
                continue;
            }
            AvailabilityEvaluator.Result r = evaluator.evaluate(
                    new ListingAvailabilityInput(l.isOpen24x7(), rulesByListing.getOrDefault(l.getId(), List.of()),
                            listingSlots, blocksByListing.getOrDefault(l.getId(), List.of()),
                            bookedSlotIds),
                    window, c.vehicleType());
            if (r.available()) {
                out.add(new Match(l, cand.distanceKm(), r.totalSlots(), r.freeSlots(),
                        pricing.quote(l, window.start(), window.end())));
            }
        }
        return out;
    }

    private static Comparator<Match> comparator(SearchCriteria c) {
        Comparator<Match> byDistance = Comparator.comparingDouble(Match::distanceKm)
                .thenComparing(m -> m.listing().getId());
        return switch (c.sort()) {
            case DISTANCE -> byDistance;
            case PRICE -> Comparator.comparing(
                            (Match m) -> m.quote() != null ? m.quote().totalAmount() : m.listing().getPricePerHour())
                    .thenComparing(byDistance);
            case RATING -> Comparator.comparing((Match m) -> m.listing().getAvgRating()).reversed()
                    .thenComparing(Comparator.comparingInt((Match m) -> m.listing().getReviewCount()).reversed())
                    .thenComparing(byDistance);
        };
    }

    /** Photos and amenities are only loaded for the page being returned. */
    private List<SearchResultDto> toDtos(List<Match> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        List<Long> ids = page.stream().map(m -> m.listing().getId()).toList();
        Map<Long, String> cover = new HashMap<>();
        for (ListingPhoto p : photos.findByListingIdInOrderByListingIdAscSortOrderAsc(ids)) {
            cover.putIfAbsent(p.getListing().getId(), mapper.photoUrl(p));
        }
        Map<Long, Set<Amenity>> amenities = new HashMap<>();
        for (Object[] row : listings.findAmenityRowsByListingIdIn(ids)) {
            amenities.computeIfAbsent((Long) row[0], k -> EnumSet.noneOf(Amenity.class)).add((Amenity) row[1]);
        }
        return page.stream().map(m -> {
            ParkingListing l = m.listing();
            return new SearchResultDto(l.getId(), l.getTitle(), l.getListingType(), l.getAddress(),
                    l.getCity().getName(), l.getCity().getState().getName(), l.getLat(), l.getLng(),
                    BigDecimal.valueOf(m.distanceKm()).setScale(1, RoundingMode.HALF_UP).doubleValue(),
                    cover.get(l.getId()), l.getPricePerHour(), l.getPricePerDay(), l.getPricePerMonth(),
                    List.copyOf(amenities.getOrDefault(l.getId(), Set.of())), l.isOpen24x7(), l.getAvgRating(),
                    l.getReviewCount(), m.totalSlots(), m.freeSlots(),
                    m.quote() == null ? null : QuoteDto.from(m.quote()));
        }).toList();
    }
}
