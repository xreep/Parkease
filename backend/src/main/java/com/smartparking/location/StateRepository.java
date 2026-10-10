package com.smartparking.location;

import com.smartparking.location.dto.StateDto;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StateRepository extends JpaRepository<State, Long> {

    Optional<State> findBySlug(String slug);

    @Query("""
            select new com.smartparking.location.dto.StateDto(
                s.id, s.name, s.code, s.slug, s.type, s.capitalName, count(c.id))
            from State s left join City c on c.state = s and c.active = true
            group by s.id, s.name, s.code, s.slug, s.type, s.capitalName
            order by s.name""")
    List<StateDto> findAllWithCityCounts();

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsByCodeIgnoreCase(String code);

    /** A state and the number of its cities, active or not. */
    interface StateCityCount {
        State getState();

        long getCityCount();
    }

    @Query("""
            select s as state, count(c.id) as cityCount
            from State s left join City c on c.state = s
            group by s
            order by s.name""")
    List<StateCityCount> findAllWithTotalCityCounts();
}
