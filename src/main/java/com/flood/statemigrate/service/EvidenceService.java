package com.flood.statemigrate.service;

import com.flood.statemigrate.domain.EvidenceEntry;
import com.flood.statemigrate.domain.EvidenceKind;
import com.flood.statemigrate.repo.EvidenceEntryRepo;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 事件证据账查询：每次风险结论使用的输入、规则与状态版本，
 * 区分旧输出、新候选输出、新输出与已撤回范围。
 */
@Service
public class EvidenceService {

    private final EvidenceEntryRepo evidenceRepo;

    public EvidenceService(EvidenceEntryRepo evidenceRepo) {
        this.evidenceRepo = evidenceRepo;
    }

    public List<EvidenceEntry> query(Long streamId, Integer partitionNo, EvidenceKind kind) {
        if (partitionNo != null && kind != null) {
            return evidenceRepo.findByStreamIdAndPartitionNoAndKind(streamId, partitionNo, kind);
        }
        if (partitionNo != null) {
            return evidenceRepo.findByStreamIdAndPartitionNoOrderByIdAsc(streamId, partitionNo);
        }
        if (kind != null) {
            return evidenceRepo.findByStreamIdAndKind(streamId, kind);
        }
        return evidenceRepo.findByStreamIdOrderByIdAsc(streamId);
    }
}
