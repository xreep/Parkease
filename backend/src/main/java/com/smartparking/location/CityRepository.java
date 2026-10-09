package com.smartparking.location;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CityRepository extends JpaRepository<City, Long> {

    @Query("select c from City c join fetch c.state where c.state.id = :stateId and c.active = true "
            + "order by c.capital desc, c.name")
    List<City> findByStateId(Long stateId);

    @Query("select c from City c join fetch c.state s where s.slug = :stateSlug and c.slug = :citySlug")
    Optional<City> findBySlugs(String stateSlug, String citySlug);

    @Query("select c from City c join fetch c.state where c.active = true "
            + "and lower(c.name) like lower(concat(:prefix, '%')) order by c.capital desc, c.name")
    List<City> searchByPrefix(String prefix, Limit limit);

    @Query("select c from City c join fetch c.state where c.capital = true and c.active = true order by c.name")
    List<City> findCapitals(Limit limit);

    long countByStateId(Long stateId);

    boolean existsByStateIdAndSlug(Long stateId, String slug);

    boolean existsByStateIdAndSlugAndIdNot(Long stateId, String slug, Long id);

    /**
     * Admin city search. {@code stateId} 0 matches every state; {@code activeMode}: 0 = all, 1 = active only,
     * 2 = inactive only; {@code pattern} is a lower-cased, backslash-escaped LIKE pattern on the name.
     */
    @Query(value = """
            select c from City c join fetch c.state s
            where (:stateId = 0L or s.id = :stateId)
              and (:activeMode = 0 or (:activeMode = 1 and c.active = true) or (:activeMode = 2 and c.active = false))
              and lower(c.name) like :pattern escape '\\'
            order by s.name, c.name, c.id
            """, countQuery = """
            select count(c) from City c join c.state s
            where (:stateId = 0L or s.id = :stateId)
              and (:activeMode = 0 or (:activeMode = 1 and c.active = true) or (:activeMode = 2 and c.active = false))
              and lower(c.name) like :pattern escape '\\'
            """)
    Page<City> adminSearch(@Param("stateId") long stateId, @Param("activeMode") int activeMode,
                           @Param("pattern") String pattern, Pageable pageable);
}
