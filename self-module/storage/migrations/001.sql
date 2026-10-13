-- Laner experience proposal v1.1. Business schema belongs to this .ails package.
-- Times are UTC epoch milliseconds; unknown timestamps remain NULL.
CREATE TABLE contexts (
 context_id TEXT NOT NULL PRIMARY KEY,
 context_kind TEXT NOT NULL CHECK(context_kind IN ('REAL','HYPOTHETICAL','FICTION','SIMULATION')),
 label TEXT NOT NULL
);
CREATE TABLE scopes (
 scope_id TEXT NOT NULL PRIMARY KEY,
 context_id TEXT NOT NULL REFERENCES contexts(context_id),
 label TEXT NOT NULL,
 UNIQUE(scope_id,context_id)
);
CREATE TABLE actors (
 actor_id TEXT NOT NULL PRIMARY KEY,
 display_name TEXT NOT NULL,
 actor_type TEXT NOT NULL CHECK(actor_type IN ('PERSON','AI','SYSTEM','ORGANIZATION','FICTIONAL_CHARACTER')),
 context_id TEXT NOT NULL REFERENCES contexts(context_id),
 created_at_ms INTEGER NOT NULL CHECK(created_at_ms>=0)
);
CREATE TABLE relationships (
 relationship_id TEXT NOT NULL PRIMARY KEY,
 actor_a_id TEXT NOT NULL REFERENCES actors(actor_id),
 actor_b_id TEXT NOT NULL REFERENCES actors(actor_id),
 dimension TEXT NOT NULL,
 context_id TEXT NOT NULL REFERENCES contexts(context_id),
 scope_id TEXT NOT NULL,
 created_at_ms INTEGER NOT NULL CHECK(created_at_ms>=0),
 CHECK(actor_a_id<actor_b_id),
 FOREIGN KEY(scope_id,context_id) REFERENCES scopes(scope_id,context_id),
 UNIQUE(actor_a_id,actor_b_id,dimension,context_id,scope_id)
);
CREATE TABLE events (
 event_id TEXT NOT NULL PRIMARY KEY,
 title TEXT NOT NULL,
 occurred_from_ms INTEGER,
 occurred_to_ms INTEGER,
 time_precision TEXT NOT NULL CHECK(time_precision IN ('EXACT','DATE','RANGE','UNKNOWN')),
 recorded_at_ms INTEGER NOT NULL CHECK(recorded_at_ms>=0),
 context_id TEXT NOT NULL REFERENCES contexts(context_id),
 event_type TEXT NOT NULL,
 summary_version_id TEXT REFERENCES interpretations(version_id) DEFERRABLE INITIALLY DEFERRED,
 lifecycle_status TEXT NOT NULL CHECK(lifecycle_status IN ('OPEN','ENDED','FOLLOWUP_PENDING')),
 CHECK(occurred_from_ms IS NULL OR occurred_to_ms IS NULL OR occurred_to_ms>=occurred_from_ms)
);
CREATE TABLE source_records (
 record_id TEXT NOT NULL PRIMARY KEY,
 source_system TEXT NOT NULL,
 source_container_id TEXT NOT NULL,
 source_object_id TEXT NOT NULL,
 fragment_locator TEXT NOT NULL DEFAULT '',
 author_actor_id TEXT REFERENCES actors(actor_id),
 collector_actor_id TEXT REFERENCES actors(actor_id),
 source_time_ms INTEGER,
 recorded_at_ms INTEGER NOT NULL CHECK(recorded_at_ms>=0),
 record_kind TEXT NOT NULL CHECK(record_kind IN ('MESSAGE','TOOL_RESULT','IMAGE','AUDIO','VIDEO','FILE')),
 content_text TEXT,
 attachment_ref TEXT,
 content_sha256 TEXT,
 availability TEXT NOT NULL CHECK(availability IN ('PRESENT','MISSING','DELETED')),
 derived_from_record_id TEXT REFERENCES source_records(record_id),
 CHECK(availability!='PRESENT' OR content_text IS NOT NULL OR attachment_ref IS NOT NULL),
 CHECK(derived_from_record_id IS NULL OR derived_from_record_id!=record_id),
 UNIQUE(source_system,source_container_id,source_object_id,fragment_locator)
);
CREATE TABLE actor_aliases (
 actor_id TEXT NOT NULL REFERENCES actors(actor_id),
 alias TEXT NOT NULL,
 source_record_id TEXT REFERENCES source_records(record_id),
 PRIMARY KEY(actor_id,alias)
);
CREATE TABLE event_participants (
 participation_id TEXT NOT NULL PRIMARY KEY,
 event_id TEXT NOT NULL REFERENCES events(event_id),
 actor_id TEXT NOT NULL REFERENCES actors(actor_id),
 participation_kind TEXT NOT NULL CHECK(participation_kind IN ('PARTICIPANT','WITNESS','MENTIONED')),
 role TEXT NOT NULL,
 stage_key TEXT NOT NULL DEFAULT 'whole_event',
 involved_from_ms INTEGER,
 involved_to_ms INTEGER,
 CHECK(involved_from_ms IS NULL OR involved_to_ms IS NULL OR involved_to_ms>=involved_from_ms),
 UNIQUE(event_id,actor_id,participation_kind,role,stage_key)
);
CREATE TABLE event_links (
 link_id TEXT NOT NULL PRIMARY KEY,
 from_event_id TEXT NOT NULL REFERENCES events(event_id),
 to_event_id TEXT NOT NULL REFERENCES events(event_id),
 link_kind TEXT NOT NULL CHECK(link_kind IN ('CONTEXT','FOLLOWUP','SUBEVENT','RELATED','VALIDATES','CORRECTS','CAUSAL_CLAIM')),
 asserted_by_actor_id TEXT NOT NULL REFERENCES actors(actor_id),
 description TEXT,
 epistemic_kind TEXT NOT NULL CHECK(epistemic_kind IN ('DIRECT','SELF_REPORT','REPORTED','INFERENCE','HYPOTHESIS')),
 created_at_ms INTEGER NOT NULL,
 revises_link_id TEXT REFERENCES event_links(link_id),
 CHECK(from_event_id!=to_event_id)
);
CREATE TABLE decisions (
 decision_id TEXT NOT NULL PRIMARY KEY,
 event_id TEXT NOT NULL REFERENCES events(event_id),
 decider_actor_id TEXT NOT NULL REFERENCES actors(actor_id),
 proposer_actor_id TEXT REFERENCES actors(actor_id),
 decided_at_ms INTEGER,
 recorded_at_ms INTEGER NOT NULL,
 goal TEXT,
 known_conditions TEXT,
 constraints_text TEXT,
 choice_text TEXT NOT NULL,
 stated_reason TEXT,
 expected_result TEXT,
 validation_standard TEXT,
 revises_decision_id TEXT REFERENCES decisions(decision_id),
 CHECK(revises_decision_id IS NULL OR revises_decision_id!=decision_id),
 UNIQUE(decision_id,event_id)
);
CREATE TABLE executions (
 execution_id TEXT NOT NULL PRIMARY KEY,
 event_id TEXT NOT NULL REFERENCES events(event_id),
 decision_id TEXT,
 executor_actor_id TEXT NOT NULL REFERENCES actors(actor_id),
 attempt_number INTEGER NOT NULL CHECK(attempt_number>0),
 action_text TEXT NOT NULL,
 started_at_ms INTEGER,
 ended_at_ms INTEGER,
 execution_status TEXT NOT NULL CHECK(execution_status IN ('NOT_STARTED','RUNNING','COMPLETED','FAILED','CANCELLED','UNCERTAIN')),
 runtime_environment TEXT,
 session_id TEXT,
 model_version TEXT,
 source_system TEXT,
 tool_call_id TEXT,
 recorded_at_ms INTEGER NOT NULL,
 CHECK(started_at_ms IS NULL OR ended_at_ms IS NULL OR ended_at_ms>=started_at_ms),
 FOREIGN KEY(decision_id,event_id) REFERENCES decisions(decision_id,event_id),
 UNIQUE(decision_id,attempt_number),
 UNIQUE(source_system,tool_call_id),
 UNIQUE(execution_id,event_id)
);
CREATE TABLE observations (
 observation_id TEXT NOT NULL PRIMARY KEY,
 source_event_id TEXT NOT NULL REFERENCES events(event_id),
 target_event_id TEXT NOT NULL REFERENCES events(event_id),
 decision_id TEXT,
 execution_id TEXT,
 observer_actor_id TEXT NOT NULL REFERENCES actors(actor_id),
 original_speaker_actor_id TEXT REFERENCES actors(actor_id),
 observation_kind TEXT NOT NULL CHECK(observation_kind IN ('TOOL_RECEIPT','RESULT','FEEDBACK','SELF_EVALUATION')),
 content_text TEXT NOT NULL,
 observed_at_ms INTEGER,
 recorded_at_ms INTEGER NOT NULL,
 evaluation_standard TEXT,
 epistemic_kind TEXT NOT NULL CHECK(epistemic_kind IN ('DIRECT','SELF_REPORT','REPORTED','INFERENCE','HYPOTHESIS')),
 FOREIGN KEY(decision_id,target_event_id) REFERENCES decisions(decision_id,event_id),
 FOREIGN KEY(execution_id,target_event_id) REFERENCES executions(execution_id,event_id)
);
CREATE TABLE interpretation_series (
 series_id TEXT NOT NULL PRIMARY KEY,
 judger_actor_id TEXT NOT NULL REFERENCES actors(actor_id),
 interpretation_kind TEXT NOT NULL CHECK(interpretation_kind IN ('EVENT_SUMMARY','ACTOR_ATTRIBUTE','ACTOR_PORTRAIT','RELATIONSHIP_STATE','SELF_MODEL','METHOD','RETROSPECTIVE')),
 target_actor_id TEXT REFERENCES actors(actor_id),
 target_event_id TEXT REFERENCES events(event_id),
 target_decision_id TEXT REFERENCES decisions(decision_id),
 target_execution_id TEXT REFERENCES executions(execution_id),
 target_relationship_id TEXT REFERENCES relationships(relationship_id),
 scope_id TEXT NOT NULL REFERENCES scopes(scope_id),
 CHECK((target_actor_id IS NOT NULL)+(target_event_id IS NOT NULL)+(target_decision_id IS NOT NULL)+(target_execution_id IS NOT NULL)+(target_relationship_id IS NOT NULL)=1),
 CHECK((interpretation_kind='RELATIONSHIP_STATE')=(target_relationship_id IS NOT NULL)),
 CHECK(interpretation_kind!='EVENT_SUMMARY' OR target_event_id IS NOT NULL),
 UNIQUE(series_id,interpretation_kind),
 UNIQUE(series_id,target_relationship_id,interpretation_kind)
);
CREATE TABLE interpretations (
 version_id TEXT NOT NULL PRIMARY KEY,
 series_id TEXT NOT NULL,
 interpretation_kind TEXT NOT NULL,
 recorder_actor_id TEXT NOT NULL REFERENCES actors(actor_id),
 content_text TEXT NOT NULL,
 epistemic_kind TEXT NOT NULL CHECK(epistemic_kind IN ('DIRECT','SELF_REPORT','REPORTED','INFERENCE','HYPOTHESIS')),
 confidence TEXT CHECK(confidence IN ('LOW','MEDIUM','HIGH')),
 confidence_reason TEXT,
 formed_at_ms INTEGER,
 recorded_at_ms INTEGER NOT NULL,
 valid_from_ms INTEGER,
 valid_to_ms INTEGER,
 valid_time_precision TEXT NOT NULL CHECK(valid_time_precision IN ('EXACT','DATE','RANGE','UNKNOWN')),
 previous_version_id TEXT,
 revision_reason TEXT,
 correction_event_id TEXT REFERENCES events(event_id),
 relationship_extension_id TEXT REFERENCES relationship_versions(version_id) DEFERRABLE INITIALLY DEFERRED,
 CHECK(valid_from_ms IS NULL OR valid_to_ms IS NULL OR valid_to_ms>=valid_from_ms),
 CHECK(previous_version_id IS NULL OR previous_version_id!=version_id),
 CHECK((interpretation_kind='RELATIONSHIP_STATE' AND relationship_extension_id IS NOT NULL AND relationship_extension_id=version_id) OR (interpretation_kind!='RELATIONSHIP_STATE' AND relationship_extension_id IS NULL)),
 FOREIGN KEY(series_id,interpretation_kind) REFERENCES interpretation_series(series_id,interpretation_kind),
 FOREIGN KEY(previous_version_id,series_id) REFERENCES interpretations(version_id,series_id) DEFERRABLE INITIALLY DEFERRED,
 UNIQUE(version_id,series_id),
 UNIQUE(version_id,series_id,interpretation_kind)
);
CREATE TABLE relationship_versions (
 version_id TEXT NOT NULL PRIMARY KEY,
 series_id TEXT NOT NULL,
 relationship_id TEXT NOT NULL,
 interpretation_kind TEXT NOT NULL DEFAULT 'RELATIONSHIP_STATE' CHECK(interpretation_kind='RELATIONSHIP_STATE'),
 relationship_label TEXT NOT NULL,
 actor_a_role TEXT,
 actor_b_role TEXT,
 relationship_status TEXT NOT NULL CHECK(relationship_status IN ('ACTIVE','PAUSED','ENDED','NOT_ESTABLISHED','UNCERTAIN')),
 change_kind TEXT NOT NULL CHECK(change_kind IN ('INITIAL_RECORD','STATE_CHANGE','REINTERPRETATION','CORRECTION','TIME_BOUNDARY','RETRACTION')),
 previous_stage_version_id TEXT,
 recognition_mode TEXT NOT NULL CHECK(recognition_mode IN ('UNILATERAL_EXPLICIT','JOINT_EXPLICIT','EXTERNAL_RECORD','INFERRED','UNCERTAIN')),
 confirmation_a_evidence_id TEXT,
 confirmation_b_evidence_id TEXT,
 confirmation_a_actor_id TEXT,
 confirmation_b_actor_id TEXT,
 CHECK(change_kind!='RETRACTION' OR relationship_status='UNCERTAIN'),
 CHECK((recognition_mode='JOINT_EXPLICIT' AND confirmation_a_evidence_id IS NOT NULL AND confirmation_b_evidence_id IS NOT NULL AND confirmation_a_actor_id IS NOT NULL AND confirmation_b_actor_id IS NOT NULL) OR
 (recognition_mode!='JOINT_EXPLICIT' AND confirmation_a_evidence_id IS NULL AND confirmation_b_evidence_id IS NULL AND confirmation_a_actor_id IS NULL AND confirmation_b_actor_id IS NULL)),
 FOREIGN KEY(version_id,series_id,interpretation_kind) REFERENCES interpretations(version_id,series_id,interpretation_kind) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(series_id,relationship_id,interpretation_kind) REFERENCES interpretation_series(series_id,target_relationship_id,interpretation_kind),
 FOREIGN KEY(previous_stage_version_id,relationship_id) REFERENCES relationship_versions(version_id,relationship_id),
 FOREIGN KEY(confirmation_a_evidence_id,version_id,confirmation_a_actor_id) REFERENCES evidence_refs(evidence_id,owner_interpretation_id,confirmation_actor_id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(confirmation_b_evidence_id,version_id,confirmation_b_actor_id) REFERENCES evidence_refs(evidence_id,owner_interpretation_id,confirmation_actor_id) DEFERRABLE INITIALLY DEFERRED,
 CHECK(previous_stage_version_id IS NULL OR previous_stage_version_id!=version_id),
 UNIQUE(version_id,relationship_id)
);
CREATE TABLE event_sources (
 event_id TEXT NOT NULL REFERENCES events(event_id),
 record_id TEXT NOT NULL REFERENCES source_records(record_id),
 locator TEXT NOT NULL DEFAULT '',
 purpose TEXT NOT NULL,
 PRIMARY KEY(event_id,record_id,locator,purpose)
);
CREATE TABLE evidence_refs (
 evidence_id TEXT NOT NULL PRIMARY KEY,
 owner_actor_id TEXT REFERENCES actors(actor_id),
 owner_event_id TEXT REFERENCES events(event_id),
 owner_participation_id TEXT REFERENCES event_participants(participation_id),
 owner_link_id TEXT REFERENCES event_links(link_id),
 owner_decision_id TEXT REFERENCES decisions(decision_id),
 owner_execution_id TEXT REFERENCES executions(execution_id),
 owner_observation_id TEXT REFERENCES observations(observation_id),
 owner_interpretation_id TEXT REFERENCES interpretations(version_id),
 source_record_id TEXT REFERENCES source_records(record_id),
 source_interpretation_id TEXT REFERENCES interpretations(version_id),
 locator TEXT NOT NULL DEFAULT '',
 evidence_role TEXT NOT NULL CHECK(evidence_role IN ('SUPPORTS','REFUTES','DERIVED','QUOTES','CONFIRMATION')),
 confirmation_actor_id TEXT REFERENCES actors(actor_id),
 CHECK((owner_actor_id IS NOT NULL)+(owner_event_id IS NOT NULL)+(owner_participation_id IS NOT NULL)+(owner_link_id IS NOT NULL)+(owner_decision_id IS NOT NULL)+(owner_execution_id IS NOT NULL)+(owner_observation_id IS NOT NULL)+(owner_interpretation_id IS NOT NULL)=1),
 CHECK((source_record_id IS NOT NULL)+(source_interpretation_id IS NOT NULL)=1),
 CHECK(owner_interpretation_id IS NULL OR source_interpretation_id IS NULL OR owner_interpretation_id!=source_interpretation_id),
 CHECK((evidence_role='CONFIRMATION')=(confirmation_actor_id IS NOT NULL)),
 CHECK(evidence_role!='CONFIRMATION' OR (source_record_id IS NOT NULL AND owner_interpretation_id IS NOT NULL)),
 UNIQUE(evidence_id,owner_interpretation_id,confirmation_actor_id)
);
CREATE TABLE decision_basis (
 basis_id TEXT NOT NULL PRIMARY KEY,
 decision_id TEXT NOT NULL REFERENCES decisions(decision_id),
 basis_event_id TEXT REFERENCES events(event_id),
 basis_decision_id TEXT REFERENCES decisions(decision_id),
 basis_interpretation_id TEXT REFERENCES interpretations(version_id),
 read_at_ms INTEGER,
 usage_status TEXT CHECK(usage_status IN ('RETRIEVED','LOADED','ADOPTED','REJECTED')),
 usage_explanation TEXT,
 CHECK((basis_event_id IS NOT NULL)+(basis_decision_id IS NOT NULL)+(basis_interpretation_id IS NOT NULL)=1),
 CHECK(basis_decision_id IS NULL OR basis_decision_id!=decision_id)
);
CREATE TABLE decision_actors (
 decision_id TEXT NOT NULL REFERENCES decisions(decision_id),
 actor_id TEXT NOT NULL REFERENCES actors(actor_id),
 role TEXT NOT NULL,
 PRIMARY KEY(decision_id,actor_id,role)
);
CREATE TABLE execution_actors (
 execution_id TEXT NOT NULL REFERENCES executions(execution_id),
 actor_id TEXT NOT NULL REFERENCES actors(actor_id),
 role TEXT NOT NULL,
 PRIMARY KEY(execution_id,actor_id,role)
);
CREATE TABLE relationship_events (
 version_id TEXT NOT NULL REFERENCES relationship_versions(version_id) DEFERRABLE INITIALLY DEFERRED,
 event_id TEXT NOT NULL REFERENCES events(event_id),
 event_role TEXT NOT NULL CHECK(event_role IN ('FORMATION','TRANSITION','CONFIRMATION','SUPPORT','COUNTEREVIDENCE','CORRECTION')),
 PRIMARY KEY(version_id,event_id,event_role)
);
CREATE INDEX actors_by_context_name ON actors(context_id,display_name);
CREATE INDEX events_by_context_time ON events(context_id,occurred_from_ms);
CREATE INDEX records_by_source_time ON source_records(source_time_ms,recorded_at_ms);
CREATE INDEX participation_by_actor ON event_participants(actor_id,event_id);
CREATE INDEX links_by_target ON event_links(to_event_id,link_kind);
CREATE INDEX decisions_by_actor ON decisions(decider_actor_id,decided_at_ms);
CREATE INDEX executions_by_event ON executions(event_id,started_at_ms);
CREATE INDEX observations_by_target ON observations(target_event_id,observed_at_ms);
CREATE INDEX observations_by_execution ON observations(execution_id);
CREATE INDEX series_by_subject ON interpretation_series(judger_actor_id,target_relationship_id,scope_id);
CREATE INDEX interpretations_by_series_time ON interpretations(series_id,formed_at_ms,recorded_at_ms);
CREATE INDEX interpretations_by_previous ON interpretations(previous_version_id);
CREATE INDEX relationship_versions_by_relationship ON relationship_versions(relationship_id,series_id);
CREATE INDEX relationship_versions_by_previous_stage ON relationship_versions(previous_stage_version_id);
CREATE INDEX evidence_by_interpretation ON evidence_refs(owner_interpretation_id);
CREATE INDEX evidence_by_record ON evidence_refs(source_record_id);
CREATE INDEX basis_by_interpretation ON decision_basis(basis_interpretation_id);
CREATE INDEX relationship_events_by_event ON relationship_events(event_id);
CREATE TRIGGER observation_target_consistency_insert AFTER INSERT ON observations
WHEN NEW.execution_id IS NOT NULL AND NEW.decision_id IS NOT NULL
BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM executions WHERE execution_id=NEW.execution_id AND decision_id=NEW.decision_id)
 THEN RAISE(ABORT,'observation decision/execution mismatch') END;
END;
CREATE TRIGGER observation_target_consistency_update AFTER UPDATE OF decision_id,execution_id ON observations
WHEN NEW.execution_id IS NOT NULL AND NEW.decision_id IS NOT NULL
BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM executions WHERE execution_id=NEW.execution_id AND decision_id=NEW.decision_id)
 THEN RAISE(ABORT,'observation decision/execution mismatch') END;
END;
CREATE TRIGGER interpretation_cycle_insert AFTER INSERT ON interpretations
BEGIN
 SELECT CASE WHEN EXISTS(
  WITH RECURSIVE ancestors(id) AS (SELECT NEW.previous_version_id UNION SELECT i.previous_version_id FROM interpretations i JOIN ancestors a ON i.version_id=a.id WHERE i.previous_version_id IS NOT NULL)
  SELECT 1 FROM ancestors WHERE id=NEW.version_id
 ) THEN RAISE(ABORT,'interpretation revision cycle') END;
END;
CREATE TRIGGER interpretation_cycle_update AFTER UPDATE OF previous_version_id ON interpretations
BEGIN
 SELECT CASE WHEN EXISTS(
  WITH RECURSIVE ancestors(id) AS (SELECT NEW.previous_version_id UNION SELECT i.previous_version_id FROM interpretations i JOIN ancestors a ON i.version_id=a.id WHERE i.previous_version_id IS NOT NULL)
  SELECT 1 FROM ancestors WHERE id=NEW.version_id
 ) THEN RAISE(ABORT,'interpretation revision cycle') END;
END;
CREATE TRIGGER relationship_stage_consistency_insert AFTER INSERT ON relationship_versions
BEGIN
 SELECT CASE WHEN (SELECT scope_id FROM interpretation_series WHERE series_id=NEW.series_id)!=(SELECT scope_id FROM relationships WHERE relationship_id=NEW.relationship_id)
 THEN RAISE(ABORT,'relationship scope mismatch') END;
 SELECT CASE WHEN NEW.recognition_mode='JOINT_EXPLICIT' AND NOT EXISTS(SELECT 1 FROM relationships WHERE relationship_id=NEW.relationship_id AND actor_a_id=NEW.confirmation_a_actor_id AND actor_b_id=NEW.confirmation_b_actor_id)
 THEN RAISE(ABORT,'joint confirmation endpoint mismatch') END;
 SELECT CASE WHEN NEW.previous_stage_version_id IS NOT NULL AND EXISTS(
  SELECT 1 FROM relationship_versions p JOIN interpretation_series ps ON ps.series_id=p.series_id JOIN interpretation_series ns ON ns.series_id=NEW.series_id
  WHERE p.version_id=NEW.previous_stage_version_id AND (p.series_id=NEW.series_id OR ps.judger_actor_id!=ns.judger_actor_id)
 ) THEN RAISE(ABORT,'relationship stage owner mismatch') END;
 SELECT CASE WHEN EXISTS(
  WITH RECURSIVE ancestors(id) AS (SELECT NEW.previous_stage_version_id UNION SELECT r.previous_stage_version_id FROM relationship_versions r JOIN ancestors a ON r.version_id=a.id WHERE r.previous_stage_version_id IS NOT NULL)
  SELECT 1 FROM ancestors WHERE id=NEW.version_id
 ) THEN RAISE(ABORT,'relationship stage cycle') END;
END;
CREATE TRIGGER relationship_stage_consistency_update AFTER UPDATE ON relationship_versions
BEGIN
 SELECT CASE WHEN (SELECT scope_id FROM interpretation_series WHERE series_id=NEW.series_id)!=(SELECT scope_id FROM relationships WHERE relationship_id=NEW.relationship_id)
 THEN RAISE(ABORT,'relationship scope mismatch') END;
 SELECT CASE WHEN NEW.recognition_mode='JOINT_EXPLICIT' AND NOT EXISTS(SELECT 1 FROM relationships WHERE relationship_id=NEW.relationship_id AND actor_a_id=NEW.confirmation_a_actor_id AND actor_b_id=NEW.confirmation_b_actor_id)
 THEN RAISE(ABORT,'joint confirmation endpoint mismatch') END;
 SELECT CASE WHEN NEW.previous_stage_version_id IS NOT NULL AND EXISTS(
  SELECT 1 FROM relationship_versions p JOIN interpretation_series ps ON ps.series_id=p.series_id JOIN interpretation_series ns ON ns.series_id=NEW.series_id
  WHERE p.version_id=NEW.previous_stage_version_id AND (p.series_id=NEW.series_id OR ps.judger_actor_id!=ns.judger_actor_id)
 ) THEN RAISE(ABORT,'relationship stage owner mismatch') END;
 SELECT CASE WHEN EXISTS(
  WITH RECURSIVE ancestors(id) AS (SELECT NEW.previous_stage_version_id UNION SELECT r.previous_stage_version_id FROM relationship_versions r JOIN ancestors a ON r.version_id=a.id WHERE r.previous_stage_version_id IS NOT NULL)
  SELECT 1 FROM ancestors WHERE id=NEW.version_id
 ) THEN RAISE(ABORT,'relationship stage cycle') END;
END;
CREATE TRIGGER confirmation_source_insert AFTER INSERT ON evidence_refs WHEN NEW.evidence_role='CONFIRMATION'
BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM source_records WHERE record_id=NEW.source_record_id AND author_actor_id=NEW.confirmation_actor_id AND availability='PRESENT')
 THEN RAISE(ABORT,'confirmation speaker/source mismatch') END;
END;
CREATE TRIGGER confirmation_source_update AFTER UPDATE ON evidence_refs WHEN NEW.evidence_role='CONFIRMATION'
BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM source_records WHERE record_id=NEW.source_record_id AND author_actor_id=NEW.confirmation_actor_id AND availability='PRESENT')
 THEN RAISE(ABORT,'confirmation speaker/source mismatch') END;
END;
CREATE TRIGGER interpretation_series_identity_immutable BEFORE UPDATE ON interpretation_series
BEGIN
 SELECT RAISE(ABORT,'create a new series to change judgment ownership or target');
END;
CREATE TRIGGER relationship_identity_immutable BEFORE UPDATE ON relationships
BEGIN
 SELECT RAISE(ABORT,'create a new relationship object to change its endpoints or scope');
END;
CREATE TRIGGER subevent_cycle_insert AFTER INSERT ON event_links WHEN NEW.link_kind='SUBEVENT'
BEGIN
 SELECT CASE WHEN EXISTS(
  WITH RECURSIVE ancestors(id) AS (SELECT NEW.to_event_id UNION SELECT l.to_event_id FROM event_links l JOIN ancestors a ON l.from_event_id=a.id WHERE l.link_kind='SUBEVENT' AND NOT EXISTS(SELECT 1 FROM event_links n WHERE n.revises_link_id=l.link_id))
  SELECT 1 FROM ancestors WHERE id=NEW.from_event_id
 ) THEN RAISE(ABORT,'subevent cycle') END;
END;
CREATE TRIGGER subevent_cycle_update AFTER UPDATE ON event_links WHEN NEW.link_kind='SUBEVENT'
BEGIN
 SELECT CASE WHEN EXISTS(
  WITH RECURSIVE ancestors(id) AS (SELECT NEW.to_event_id UNION SELECT l.to_event_id FROM event_links l JOIN ancestors a ON l.from_event_id=a.id WHERE l.link_kind='SUBEVENT' AND NOT EXISTS(SELECT 1 FROM event_links n WHERE n.revises_link_id=l.link_id))
  SELECT 1 FROM ancestors WHERE id=NEW.from_event_id
 ) THEN RAISE(ABORT,'subevent cycle') END;
END;
CREATE TRIGGER event_summary_insert AFTER INSERT ON events WHEN NEW.summary_version_id IS NOT NULL
BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM interpretations i JOIN interpretation_series s ON s.series_id=i.series_id WHERE i.version_id=NEW.summary_version_id AND s.target_event_id=NEW.event_id AND i.interpretation_kind='EVENT_SUMMARY')
 THEN RAISE(ABORT,'event summary target mismatch') END;
END;
CREATE TRIGGER event_summary_update AFTER UPDATE OF summary_version_id ON events WHEN NEW.summary_version_id IS NOT NULL
BEGIN
 SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM interpretations i JOIN interpretation_series s ON s.series_id=i.series_id WHERE i.version_id=NEW.summary_version_id AND s.target_event_id=NEW.event_id AND i.interpretation_kind='EVENT_SUMMARY')
 THEN RAISE(ABORT,'event summary target mismatch') END;
END;
CREATE VIEW relationship_history AS
 SELECT r.relationship_id,r.actor_a_id,r.actor_b_id,r.dimension,r.context_id,r.scope_id,
 s.judger_actor_id,i.recorder_actor_id,i.series_id,i.version_id,i.content_text,i.epistemic_kind,i.confidence,
 i.formed_at_ms,i.recorded_at_ms,i.valid_from_ms,i.valid_to_ms,i.valid_time_precision,i.previous_version_id,i.revision_reason,
 v.relationship_label,v.actor_a_role,v.actor_b_role,v.relationship_status,v.change_kind,v.recognition_mode,v.previous_stage_version_id,
 CASE WHEN v.recognition_mode!='JOINT_EXPLICIT' THEN NULL ELSE
  (SELECT COUNT(*) FROM evidence_refs e JOIN source_records sr ON sr.record_id=e.source_record_id
   WHERE e.evidence_id IN (v.confirmation_a_evidence_id,v.confirmation_b_evidence_id) AND e.owner_interpretation_id=v.version_id
   AND sr.availability='PRESENT' AND sr.author_actor_id=e.confirmation_actor_id)=2 END AS confirmation_sources_available
 FROM relationship_versions v JOIN relationships r ON r.relationship_id=v.relationship_id
 JOIN interpretations i ON i.version_id=v.version_id JOIN interpretation_series s ON s.series_id=i.series_id;
CREATE VIEW relationship_current_candidates AS
 SELECT h.* FROM relationship_history h
 WHERE NOT EXISTS(SELECT 1 FROM interpretations n WHERE n.previous_version_id=h.version_id
 AND n.recorded_at_ms<=CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)
 AND (n.formed_at_ms IS NULL OR n.formed_at_ms<=CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)))
 AND (h.formed_at_ms IS NULL OR h.formed_at_ms<=CAST((julianday('now')-2440587.5)*86400000 AS INTEGER))
 AND h.recorded_at_ms<=CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)
 AND (h.valid_from_ms IS NULL OR h.valid_from_ms<=CAST((julianday('now')-2440587.5)*86400000 AS INTEGER))
 AND (h.valid_to_ms IS NULL OR h.valid_to_ms>CAST((julianday('now')-2440587.5)*86400000 AS INTEGER));
CREATE VIEW relationship_current AS
 SELECT c.*,(SELECT COUNT(*) FROM relationship_current_candidates n WHERE n.relationship_id=c.relationship_id AND n.judger_actor_id=c.judger_actor_id) AS concurrent_candidate_count,
 CASE WHEN c.valid_time_precision='UNKNOWN' OR c.valid_from_ms IS NULL THEN 'LATEST_KNOWN_TIME_UNCERTAIN' ELSE 'VALID_AT_QUERY_TIME' END AS temporal_basis
 FROM relationship_current_candidates c;
CREATE VIEW interpretation_heads AS
 SELECT i.* FROM interpretations i WHERE NOT EXISTS(SELECT 1 FROM interpretations n WHERE n.previous_version_id=i.version_id);
