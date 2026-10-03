package com.apop.fraud.service;

import com.apop.fraud.domain.SubjectHasher;
import com.apop.fraud.domain.UuidV7;
import com.apop.fraud.messaging.AssessmentOutbox;
import com.apop.fraud.persistence.BeneficiaryEntry;
import com.apop.fraud.persistence.BeneficiaryRepository;
import com.apop.fraud.persistence.CountryEntry;
import com.apop.fraud.persistence.CountryListRepository;
import com.apop.fraud.persistence.DeviceEntry;
import com.apop.fraud.persistence.DeviceListRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class RestrictedListService {

    private final BeneficiaryRepository beneficiaries;
    private final DeviceListRepository devices;
    private final CountryListRepository countries;
    private final AssessmentOutbox outbox;
    private final Clock clock;
    private final String pepper;

    public RestrictedListService(
            BeneficiaryRepository beneficiaries,
            DeviceListRepository devices,
            CountryListRepository countries,
            AssessmentOutbox outbox,
            Clock clock,
            String fraudPepper) {
        this.beneficiaries = beneficiaries;
        this.devices = devices;
        this.countries = countries;
        this.outbox = outbox;
        this.clock = clock;
        this.pepper = fraudPepper;
    }

    @Transactional
    public ListView propose(String subjectType, String listType, String value, String actor) {
        if (actor == null || actor.isBlank()) {
            throw FraudException.badRequest("X-Actor-Id is required");
        }
        String hash = SubjectHasher.hash(pepper, "COUNTRY".equals(subjectType) ? value.trim().toUpperCase() : value.trim());
        UUID id = UuidV7.generate();
        boolean deny = "DENY".equals(listType);
        String approver = deny ? null : actor;
        return switch (subjectType) {
            case "CREDITOR" -> {
                BeneficiaryEntry entry = beneficiaries.findByListTypeAndCreditorHash(listType, hash)
                        .orElseGet(() -> beneficiaries.save(BeneficiaryEntry.create(id, listType, hash, clock.instant(), actor, approver)));
                yield view("CREDITOR", entry.getListEntryId(), listType, entry.getApprovedBy() != null, entry.getCreatedBy());
            }
            case "DEVICE" -> {
                if (!deny) {
                    throw FraudException.badRequest("Device lists are deny lists");
                }
                DeviceEntry entry = devices.findByDeviceHash(hash)
                        .orElseGet(() -> devices.save(DeviceEntry.create(id, hash, clock.instant(), actor)));
                yield view("DEVICE", entry.getListEntryId(), "DENY", entry.getApprovedBy() != null, entry.getCreatedBy());
            }
            case "COUNTRY" -> {
                if (!deny) {
                    throw FraudException.badRequest("Country lists are deny lists");
                }
                CountryEntry entry = countries.findByCountryHash(hash)
                        .orElseGet(() -> countries.save(CountryEntry.create(id, hash, clock.instant(), actor)));
                yield view("COUNTRY", entry.getListEntryId(), "DENY", entry.getApprovedBy() != null, entry.getCreatedBy());
            }
            default -> throw FraudException.badRequest("Unsupported list subject");
        };
    }

    @Transactional
    public ListView approve(UUID listEntryId, String actor) {
        if (actor == null || actor.isBlank()) {
            throw FraudException.badRequest("X-Actor-Id is required");
        }
        BeneficiaryEntry beneficiary = beneficiaries.findById(listEntryId).orElse(null);
        if (beneficiary != null) {
            return approveBeneficiary(beneficiary, actor);
        }
        DeviceEntry device = devices.findById(listEntryId).orElse(null);
        if (device != null) {
            return approveDevice(device, actor);
        }
        CountryEntry country = countries.findById(listEntryId).orElseThrow(() -> FraudException.notFound("List entry was not found"));
        if (actor.equals(country.getCreatedBy())) {
            throw FraudException.forbidden("A second person must approve a deny entry");
        }
        if (country.getApprovedBy() == null) {
            country.approve(actor);
            audit(listEntryId, "COUNTRY", actor);
        }
        return view("COUNTRY", country.getListEntryId(), "DENY", true, country.getCreatedBy());
    }

    private ListView approveBeneficiary(BeneficiaryEntry entry, String actor) {
        if ("DENY".equals(entry.getListType()) && actor.equals(entry.getCreatedBy())) {
            throw FraudException.forbidden("A second person must approve a deny entry");
        }
        if (entry.getApprovedBy() == null) {
            entry.approve(actor);
            audit(entry.getListEntryId(), "CREDITOR", actor);
        }
        return view("CREDITOR", entry.getListEntryId(), entry.getListType(), true, entry.getCreatedBy());
    }

    private ListView approveDevice(DeviceEntry entry, String actor) {
        if (actor.equals(entry.getCreatedBy())) {
            throw FraudException.forbidden("A second person must approve a deny entry");
        }
        if (entry.getApprovedBy() == null) {
            entry.approve(actor);
            audit(entry.getListEntryId(), "DEVICE", actor);
        }
        return view("DEVICE", entry.getListEntryId(), "DENY", true, entry.getCreatedBy());
    }

    private void audit(UUID listEntryId, String subjectType, String actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listEntryId", listEntryId);
        body.put("subjectType", subjectType);
        body.put("actor", actor);
        outbox.audit(listEntryId, "RestrictedListApproved", body);
    }

    private static ListView view(String subjectType, UUID id, String listType, boolean active, String createdBy) {
        return new ListView(id, subjectType, listType, active ? "ACTIVE" : "PENDING", createdBy);
    }

    public record ListView(UUID listEntryId, String subjectType, String listType, String status, String createdBy) {
    }
}
