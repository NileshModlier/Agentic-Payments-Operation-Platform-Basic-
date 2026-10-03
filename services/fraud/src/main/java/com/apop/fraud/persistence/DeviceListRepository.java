package com.apop.fraud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeviceListRepository extends JpaRepository<DeviceEntry, UUID> {

    Optional<DeviceEntry> findByDeviceHash(String deviceHash);
}
