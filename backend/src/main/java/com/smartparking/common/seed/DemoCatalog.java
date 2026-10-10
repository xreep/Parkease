package com.smartparking.common.seed;

import com.smartparking.dispute.DisputeCategory;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** The names, plates and sentences the demo activity is made of. Pure data plus tiny pickers; no database access. */
final class DemoCatalog {

    private DemoCatalog() {
    }

    /** Existing demo owners (created by {@link DemoListingSeeder}) whose listings take part in the activity. */
    static final List<String> EXISTING_OWNER_EMAILS = List.of("owner@parkease.dev", "owner.north@parkease.dev",
            "owner.south@parkease.dev", "owner.east@parkease.dev", "owner.northeast@parkease.dev",
            "owner.central@parkease.dev");

    /** Extra owners: o01..o20 are verified, o21..o23 wait for verification, o24 was rejected. */
    static final List<String> NEW_OWNER_NAMES = List.of("Rajesh Malhotra", "Sunita Rao", "Imran Sheikh",
            "Lakshmi Narayanan", "Debashish Banerjee", "Anjali Reddy", "Harpreet Gill", "Farhan Qureshi",
            "Meera Nair", "Vivek Choudhary", "Pooja Mehta", "Gurpreet Singh", "Ritu Agarwal", "Sanjay Patil",
            "Divya Krishnan", "Rohit Saxena", "Ananya Ghosh", "Tarun Bhatia", "Shreya Kulkarni", "Naveen Kumar",
            "Kavita Deshmukh", "Manish Tiwari", "Zoya Khan", "Pradeep Yadav");
    static final int VERIFIED_NEW_OWNERS = 20;
    static final int PENDING_NEW_OWNERS = 3;

    static final List<String> FIRST_NAMES = List.of("Aarav", "Vihaan", "Aditya", "Arjun", "Sai", "Krishna", "Ishaan",
            "Rohan", "Kabir", "Dhruv", "Karan", "Siddharth", "Nikhil", "Pranav", "Yash", "Aniket", "Varun", "Tushar",
            "Aditi", "Ananya", "Diya", "Ishita", "Kavya", "Myra", "Nisha", "Priyanka", "Riya", "Sanya", "Tanvi",
            "Neha", "Pallavi", "Shruti", "Swati", "Isha", "Kiran", "Mohit", "Ramesh", "Suresh", "Vikas", "Gaurav");
    static final List<String> LAST_NAMES = List.of("Sharma", "Verma", "Gupta", "Iyer", "Nair", "Reddy", "Patel",
            "Mehta", "Shah", "Singh", "Kapoor", "Joshi", "Kulkarni", "Deshmukh", "Banerjee", "Chatterjee", "Das",
            "Menon", "Pillai", "Rao", "Naidu", "Chopra", "Malhotra", "Bhatt", "Saxena", "Mishra", "Pandey", "Tiwari",
            "Khan", "Ansari", "Fernandes", "D'Souza", "Thomas", "Sethi", "Bose", "Mukherjee");

    static final List<String> PLATE_STATES = List.of("MH", "DL", "KA", "TN", "TS", "WB", "GJ", "RJ", "UP", "HR", "KL",
            "MP", "PB", "AP");
    static final List<String> FOUR_WHEELERS = List.of("Maruti Suzuki Swift", "Hyundai Creta", "Tata Nexon",
            "Honda City", "Mahindra XUV700", "Kia Seltos", "Toyota Innova Crysta", "Maruti Baleno", "Hyundai i20",
            "Tata Punch", "Maruti Brezza", "Skoda Slavia");
    static final List<String> TWO_WHEELERS = List.of("Honda Activa 6G", "TVS Jupiter", "Royal Enfield Classic 350",
            "Bajaj Pulsar 150", "Hero Splendor Plus", "Yamaha FZ-S", "Suzuki Access 125", "Ola S1 Pro",
            "Honda Shine", "TVS Apache RTR 160");

    static final List<String> PAYMENT_METHODS = List.of("upi", "upi", "upi", "card", "card", "netbanking", "wallet");

    // ---- bookings -------------------------------------------------------------------------------------------

    static final List<String> DRIVER_CANCEL_REASONS = List.of("Change of plans", "Meeting got rescheduled",
            "Found parking closer to the venue", "Trip cancelled", "Vehicle is in the garage", "Travelling by metro instead");
    static final List<String> OWNER_CANCEL_REASONS = List.of("The slot is needed for a maintenance visit",
            "Water logging in the parking area", "Access is closed for repairs that day", "Booked twice by mistake");
    static final List<String> ADMIN_CANCEL_REASONS = List.of("The listing was temporarily closed by the owner",
            "Duplicate booking reported by the driver", "Safety check failed at the property");
    static final List<String> OWNER_REJECT_REASONS = List.of("The slot is reserved for residents that day",
            "Sorry, we are closed for maintenance then", "We can't fit this vehicle in the slot");

    // ---- reviews --------------------------------------------------------------------------------------------

    private static final Map<Integer, List<String>> REVIEW_TEXTS = Map.of(
            5, List.of("Easy to find and exactly as described.", "Spotless, well lit and the owner was very responsive.",
                    "The slot was ready when I arrived. Will book again!", "Great value for the location.",
                    "Smooth entry and exit. I felt safe leaving my car here.", "Perfect for my daily commute.",
                    "Very convenient, just minutes from where I needed to be.", "Friendly staff and a hassle free booking.",
                    "Clean, covered and the QR code worked at the gate.", "Best parking I have found in this area."),
            4, List.of("Good spot, a little tight to turn in.", "Worked well, the entrance was slightly confusing.",
                    "Nice and secure. A bit pricey at peak hours.", "Reliable and close. Would like clearer signage.",
                    "Everything as listed, just a short wait at the gate.", "Solid option for the price."),
            3, List.of("Okay for the price but the entrance is hard to find.", "Average. The slot was narrower than I expected.",
                    "Fine for a couple of hours, not great overnight.", "Decent, though it was crowded when I left."),
            2, List.of("The slot was narrower than the photos suggest.", "Waited ten minutes for the gate to be opened.",
                    "Not very well lit after dark."),
            1, List.of("The slot was occupied when I arrived and it took ages to sort out.",
                    "Nothing like the description. No lighting and no one at the gate."));

    static final String HIDDEN_REVIEW_COMMENT = "Awful place. Ring the owner on 98XXXXXX21 and ask for my money back, "
            + "he never picks up.";
    static final String HIDDEN_REVIEW_REASON = "Contains a phone number and a personal accusation";

    private static final List<String> POSITIVE_REPLIES = List.of("Thank you %s, glad it worked out. See you again soon!",
            "Thanks for the kind words, %s. We look forward to hosting you again.",
            "Much appreciated, %s! Drive safe.", "Thank you for parking with us, %s.");
    private static final List<String> APOLOGY_REPLIES = List.of(
            "Sorry about the trouble, %s. We have spoken to our staff and fixed the issue.",
            "Thanks for letting us know, %s. We are adding better signage and lighting this week.",
            "We apologise for the wait, %s. A second person now covers the gate during peak hours.");

    static String reviewComment(int rating, Random rnd) {
        List<String> options = REVIEW_TEXTS.get(rating);
        return options.get(rnd.nextInt(options.size()));
    }

    static String ownerReply(int rating, String driverFirstName, Random rnd) {
        List<String> options = rating >= 4 ? POSITIVE_REPLIES : APOLOGY_REPLIES;
        return options.get(rnd.nextInt(options.size())).formatted(driverFirstName);
    }

    // ---- disputes -------------------------------------------------------------------------------------------

    record DisputeText(DisputeCategory category, String description, String ownerResponse) {
    }

    static final List<DisputeText> DISPUTE_TEXTS = List.of(
            new DisputeText(DisputeCategory.SLOT_OCCUPIED,
                    "Another car was parked in my slot when I arrived and there was nobody to help for 25 minutes.",
                    "The guard was on a break and a visitor took the slot. We have moved him and apologised to the driver."),
            new DisputeText(DisputeCategory.NO_ACCESS,
                    "The gate was locked and the QR code on my booking did not open it.",
                    "The gate sensor was being repaired that morning. The driver was let in manually after a short wait."),
            new DisputeText(DisputeCategory.OVERSTAY,
                    "The car stayed about three hours past the booked time and blocked the next booking.",
                    "I tried calling the driver several times but got no answer."),
            new DisputeText(DisputeCategory.DAMAGE,
                    "There is a fresh scratch on the rear bumper that was not there when I parked.",
                    "Our CCTV does not show any contact with the car. Happy to share the footage."),
            new DisputeText(DisputeCategory.PAYMENT,
                    "I was charged for a full day but I parked for four hours only.",
                    "The booking was made for a full day. The driver may have chosen the wrong option."),
            new DisputeText(DisputeCategory.OTHER,
                    "The listing said covered parking but my car was left in the open.",
                    "Part of the roof is under repair. We will update the listing."));

    static final List<String> ADMIN_NOTES_REFUND_FULL = List.of(
            "Owner confirmed the slot was not available. Full refund approved.",
            "Entry failed on our side and the owner could not give access. Refunded in full.");
    static final List<String> ADMIN_NOTES_REFUND_PARTIAL = List.of(
            "Gate delay confirmed by the owner. A partial refund covers the lost time.");
    static final String ADMIN_NOTES_NO_REFUND = "The booking was used as booked; CCTV shows no damage. No refund due.";
    static final String ADMIN_NOTES_WARNING = "The owner was reminded to keep the gate staffed during booked hours.";

    static String phone(Random rnd) {
        return "9" + (100_000_000 + rnd.nextInt(900_000_000));
    }

    static String plate(Random rnd) {
        String state = PLATE_STATES.get(rnd.nextInt(PLATE_STATES.size()));
        char a = (char) ('A' + rnd.nextInt(26));
        char b = (char) ('A' + rnd.nextInt(26));
        int district = 1 + rnd.nextInt(60);
        int number = 1000 + rnd.nextInt(9000);
        return "%s%02d%c%c%04d".formatted(state, district, a, b, number);
    }
}
