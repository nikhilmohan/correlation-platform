package com.acp.patternmanager.store.repo;

import com.acp.patternmanager.store.entity.PatternEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@code pattern.pattern}. */
public interface PatternRepository extends JpaRepository<PatternEntity, UUID> {

    List<PatternEntity> findByLifecycle(String lifecycle, Pageable pageable);

    long countByLifecycle(String lifecycle);

    /**
     * [ANCHOR-CONSOL] Load a pattern row taking a pessimistic write lock
     * ({@code SELECT ... FOR UPDATE}). Concurrent folds of one anchor identity serialize on this
     * row lock, so summing occurrences never loses an update (AC-C8).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PatternEntity p where p.patternId = :patternId")
    Optional<PatternEntity> findByIdForUpdate(@Param("patternId") UUID patternId);
}
