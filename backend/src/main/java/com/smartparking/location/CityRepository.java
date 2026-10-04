package com.smartparking.location;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CityRepository extends JpaRepository<City, Long> {

    @Query("select c from City c join fetch c.state where c.state.id = :stateId order by c.capital desc, c.name")
    List<City> findByStateId(Long stateId);

    @Query("select c from City c join fetch c.state s where s.slug = :stateSlug and c.slug = :citySlug")
    Optional<City> findBySlugs(String stateSlug, String citySlug);

    @Query("select c from City c join fetch c.state where lower(c.name) like lower(concat(:prefix, '%')) "
            + "order by c.capital desc, c.name")
    List<City> searchByPrefix(String prefix, Limit limit);

    @Query("select c from City c join fetch c.state where c.capital = true order by c.name")
    List<City> findCapitals(Limit limit);
}
