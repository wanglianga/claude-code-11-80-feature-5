package com.flood.statemigrate.web;

import com.flood.statemigrate.domain.StreamEvent;
import com.flood.statemigrate.service.EventService;
import com.flood.statemigrate.web.dto.EventReq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "流事件")
@RestController
@RequestMapping("/api/streams/{streamId}/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @Operation(summary = "写入事件（雨量/水位/泵站启停/设备健康/人工上报/视频识别）")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StreamEvent ingest(@PathVariable Long streamId, @Valid @RequestBody EventReq req) {
        StreamEvent e = new StreamEvent();
        e.setPartitionNo(req.partitionNo());
        e.setEventTime(req.eventTime());
        e.setType(req.type());
        e.setRoadId(req.roadId());
        e.setPumpId(req.pumpId());
        e.setReachId(req.reachId());
        e.setPayloadJson(req.payloadJson());
        return eventService.ingest(streamId, e);
    }

    @Operation(summary = "事件列表")
    @GetMapping
    public List<StreamEvent> list(@PathVariable Long streamId,
                                  @RequestParam(required = false) Integer partitionNo) {
        return partitionNo != null ? eventService.listByPartition(streamId, partitionNo)
                : eventService.list(streamId);
    }
}
