package com.acp.patternmanager.store.repo;

import com.acp.patternmanager.store.entity.ProcessedEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the {@code eventId} idempotency dedupe set. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, UUID> {

    boolean existsByEventId(UUID eventId);
}
