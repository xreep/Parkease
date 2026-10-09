package com.smartparking.booking;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Appends rows to a booking's status history. Joins the caller's transaction. */
@Component
@RequiredArgsConstructor
public class BookingEvents {

    private final BookingEventRepository events;

    public BookingEvent record(Booking booking, BookingStatus from, BookingStatus to, BookingActor actor, String note) {
        BookingEvent event = new BookingEvent();
        event.setBooking(booking);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setActor(actor);
        event.setNote(note);
        return events.save(event);
    }
}
