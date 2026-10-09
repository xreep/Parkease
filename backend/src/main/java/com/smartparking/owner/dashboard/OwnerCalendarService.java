package com.smartparking.owner.dashboard;

import static com.smartparking.owner.dashboard.DashboardRanges.startOf;

import com.smartparking.availability.AvailabilityBlock;
import com.smartparking.availability.AvailabilityBlockRepository;
import com.smartparking.booking.Booking;
import com.smartparking.booking.BookingRepository;
import com.smartparking.common.error.ApiException;
import com.smartparking.common.util.PersonNames;
import com.smartparking.listing.ParkingListingRepository;
import com.smartparking.owner.dashboard.dto.OwnerCalendarDto;
import com.smartparking.slot.ParkingSlotRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A week grid's worth of data for one of the owner's listings: slots, bookings and blocks in a date range. */
@Service
@RequiredArgsConstructor
public class OwnerCalendarService {

    static final int MAX_DAYS = 14;

    private final ParkingListingRepository listings;
    private final ParkingSlotRepository slots;
    private final BookingRepository bookings;
    private final AvailabilityBlockRepository blocks;
    private final Clock clock;

    @Transactional(readOnly = true)
    public OwnerCalendarDto calendar(Long ownerId, Long listingId, LocalDate from, LocalDate to) {
        listings.findByIdAndOwnerId(listingId, ownerId).orElseThrow(() -> ApiException.notFound("Listing not found"));
        if (from == null || to == null) {
            throw DashboardRanges.invalid("from and to are required");
        }
        DashboardRanges.validate(from, to, MAX_DAYS);
        Instant start = startOf(from);
        Instant end = startOf(to.plusDays(1));

        List<OwnerCalendarDto.CalendarSlot> slotDtos = slots.findByListingIdOrderByLabelAsc(listingId).stream()
                .filter(s -> s.isActive())
                .map(s -> new OwnerCalendarDto.CalendarSlot(s.getId(), s.getLabel())).toList();
        List<OwnerCalendarDto.CalendarBooking> bookingDtos = bookings
                .findForCalendar(listingId, start, end, clock.instant()).stream()
                .map(OwnerCalendarService::toDto).toList();
        List<OwnerCalendarDto.CalendarBlock> blockDtos = blocks.findOverlapping(List.of(listingId), start, end).stream()
                .sorted((a, b) -> a.getStartTime().compareTo(b.getStartTime()))
                .map(OwnerCalendarService::toDto).toList();
        return new OwnerCalendarDto(listingId, from, to, slotDtos, bookingDtos, blockDtos);
    }

    private static OwnerCalendarDto.CalendarBooking toDto(Booking b) {
        return new OwnerCalendarDto.CalendarBooking(b.getId(), b.getBookingCode(), b.getSlot().getId(),
                b.getStartTime(), b.getEndTime(), b.getStatus(), PersonNames.firstNameLastInitial(b.getDriver().getName()));
    }

    private static OwnerCalendarDto.CalendarBlock toDto(AvailabilityBlock b) {
        return new OwnerCalendarDto.CalendarBlock(b.getId(), b.getSlot() == null ? null : b.getSlot().getId(),
                b.getStartTime(), b.getEndTime(), b.getReason());
    }
}
