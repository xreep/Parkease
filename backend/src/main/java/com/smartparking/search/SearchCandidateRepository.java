package com.smartparking.search;

import com.smartparking.common.model.VehicleType;
import com.smartparking.listing.Amenity;
import com.smartparking.listing.ListingType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** One native query that returns the nearest APPROVED listings passing the static (non-window) filters. */
@Repository
@RequiredArgsConstructor
public class SearchCandidateRepository {

    static final int MAX_CANDIDATES = 500;
    private static final double KM_PER_DEGREE = 111.0;

    public record Candidate(long id, double distanceKm) {
    }

    private final JdbcClient jdbc;

    public List<Candidate> findCandidates(SearchCriteria c) {
        double dLat = c.radiusKm() / KM_PER_DEGREE;
        double dLng = c.radiusKm() / (KM_PER_DEGREE * Math.cos(Math.toRadians(c.lat())));

        // The literal 'APPROVED' lets the partial index idx_listings_approved_lat_lng be used.
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.distance_km FROM (
                  SELECT l.id, (6371 * acos(least(1, greatest(-1,
                           cos(radians(:lat)) * cos(radians(l.lat)) * cos(radians(l.lng) - radians(:lng))
                         + sin(radians(:lat)) * sin(radians(l.lat)))))) AS distance_km
                  FROM parking_listings l
                  WHERE l.status = 'APPROVED'
                    AND l.lat BETWEEN :minLat AND :maxLat
                    AND l.lng BETWEEN :minLng AND :maxLng
                """);
        if (!c.types().isEmpty()) {
            sql.append("    AND l.listing_type IN (:types)\n");
        }
        if (c.maxPricePerHour() != null) {
            sql.append("    AND l.price_per_hour <= :maxPrice\n");
        }
        if (c.open24x7()) {
            sql.append("    AND l.open_24x7 = TRUE\n");
        }
        if (!c.amenities().isEmpty()) {
            sql.append("""
                        AND (SELECT count(*) FROM listing_amenities a
                             WHERE a.listing_id = l.id AND a.amenity IN (:amenities)) = :amenityCount
                    """);
        }
        sql.append("    AND EXISTS (SELECT 1 FROM parking_slots s WHERE s.listing_id = l.id AND s.active");
        if (c.vehicleType() != null) {
            sql.append(" AND s.vehicle_type = :vehicleType");
        }
        sql.append(")\n) t WHERE t.distance_km <= :radius ORDER BY t.distance_km, t.id LIMIT ").append(MAX_CANDIDATES);

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString())
                .param("lat", c.lat())
                .param("lng", c.lng())
                .param("minLat", c.lat() - dLat)
                .param("maxLat", c.lat() + dLat)
                .param("minLng", c.lng() - dLng)
                .param("maxLng", c.lng() + dLng)
                .param("radius", c.radiusKm());
        if (!c.types().isEmpty()) {
            spec = spec.param("types", c.types().stream().map(ListingType::name).toList());
        }
        if (c.maxPricePerHour() != null) {
            spec = spec.param("maxPrice", c.maxPricePerHour());
        }
        if (!c.amenities().isEmpty()) {
            spec = spec.param("amenities", c.amenities().stream().map(Amenity::name).toList())
                    .param("amenityCount", (long) c.amenities().size());
        }
        if (c.vehicleType() != null) {
            spec = spec.param("vehicleType", c.vehicleType().name());
        }
        return spec.query((rs, i) -> new Candidate(rs.getLong("id"), rs.getDouble("distance_km"))).list();
    }
}
