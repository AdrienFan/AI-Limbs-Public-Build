#!/usr/bin/env python3
"""Cloud-only execution of the package-owned SQLite schema and behavioral regressions."""
import argparse
import json
import sqlite3
import zipfile

CORE = {'actors', 'events', 'source_records', 'event_participants', 'event_links',
        'decisions', 'executions', 'observations', 'interpretations', 'relationships',
        'relationship_versions'}


def check(package):
    with zipfile.ZipFile(package) as archive:
        descriptor = json.loads(archive.read('resources/storage/database.json'))
        assert descriptor['engine'] == 'sqlite' and descriptor['api_version'] == 1
        assert descriptor['schema_version'] == 1
        migrations = [json.loads(archive.read(entry['resource'])) for entry in descriptor['migrations']]
    db = sqlite3.connect(':memory:', isolation_level=None)
    db.execute('PRAGMA foreign_keys=ON')
    db.execute('BEGIN')
    for expected, migration in enumerate(migrations, 1):
        assert migration['version'] == expected
        for sql in migration['statements']:
            assert sqlite3.complete_statement(sql)
            db.execute(sql)
    db.execute('COMMIT')
    tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert CORE <= tables and len(CORE) == 11
    assert not list(db.execute('PRAGMA foreign_key_check'))
    assert all(db.execute('SELECT count(*) FROM ' + table).fetchone()[0] == 0 for table in CORE)
    passed = ['schema_creation', 'empty_core_tables', 'foreign_key_check']

    def transaction(action):
        db.execute('BEGIN')
        try:
            action()
            db.execute('COMMIT')
        except Exception:
            if db.in_transaction:
                db.execute('ROLLBACK')
            raise

    def reject(name, action):
        try:
            transaction(action)
        except sqlite3.IntegrityError:
            passed.append(name)
        else:
            raise AssertionError('Required constraint did not reject: ' + name)

    def series(sid, actor='a', relationship='rel', scope='scope'):
        db.execute('INSERT INTO interpretation_series(series_id,judger_actor_id,interpretation_kind,target_relationship_id,scope_id) VALUES(?,?,\'RELATIONSHIP_STATE\',?,?)',
                   (sid, actor, relationship, scope))

    def interpretation(vid, sid, previous=None, start=1000, end=None, formed=1000):
        db.execute('''INSERT INTO interpretations(version_id,series_id,interpretation_kind,recorder_actor_id,content_text,epistemic_kind,formed_at_ms,recorded_at_ms,valid_from_ms,valid_to_ms,valid_time_precision,previous_version_id,relationship_extension_id)
                      VALUES(?,?,'RELATIONSHIP_STATE','recorder','fixture only','INFERENCE',?,?,?,?, 'EXACT',?,?)''',
                   (vid, sid, formed, formed, start, end, previous, vid))

    def version(vid, sid, relationship='rel', previous=None, state='ACTIVE', change='INITIAL_RECORD', mode='INFERRED', confirmations=None):
        evidence_a, evidence_b, actor_a, actor_b = confirmations if confirmations else (None, None, None, None)
        db.execute('''INSERT INTO relationship_versions(version_id,series_id,relationship_id,relationship_label,relationship_status,change_kind,previous_stage_version_id,recognition_mode,confirmation_a_evidence_id,confirmation_b_evidence_id,confirmation_a_actor_id,confirmation_b_actor_id)
                      VALUES(?,?,?,'fixture collaboration',?,?,?,?,?,?,?,?)''',
                   (vid, sid, relationship, state, change, previous, mode, evidence_a, evidence_b, actor_a, actor_b))

    db.executemany('INSERT INTO contexts VALUES(?,?,?)', [('real','REAL','real fixture'),('fiction','FICTION','novel fixture')])
    db.executemany('INSERT INTO scopes VALUES(?,?,?)', [('scope','real','project'),('novel','fiction','novel')])
    for actor, context in [('a','real'),('b','real'),('recorder','real'),('fa','fiction'),('fb','fiction')]:
        db.execute('INSERT INTO actors VALUES(?,?,?,?,?)', (actor,'same display name','PERSON' if context == 'real' else 'FICTIONAL_CHARACTER',context,1000))
    for event in ['e1','e2','e3']:
        db.execute("INSERT INTO events(event_id,title,time_precision,recorded_at_ms,context_id,event_type,lifecycle_status) VALUES(?,?,'UNKNOWN',1000,'real','DISCUSSION','OPEN')",(event,event))
    for rid, author in [('ra','a'),('rb','b')]:
        db.execute("INSERT INTO source_records(record_id,source_system,source_container_id,source_object_id,author_actor_id,collector_actor_id,recorded_at_ms,record_kind,content_text,availability) VALUES(?,'fixture','conversation',?,?,'recorder',1000,'MESSAGE','explicit fixture agreement','PRESENT')",(rid,rid,author))
    db.execute("INSERT INTO relationships VALUES('rel','a','b','project','real','scope',1000)")
    db.execute("INSERT INTO relationships VALUES('rel-j','a','b','social','real','scope',1000)")
    db.execute("INSERT INTO relationships VALUES('rel-f','fa','fb','project','fiction','novel',1000)")
    for pid, role in [('p1','PROPOSER'),('p2','OBSERVER')]:
        db.execute("INSERT INTO event_participants(participation_id,event_id,actor_id,participation_kind,role) VALUES(?,'e1','a','PARTICIPANT',?)",(pid,role))
    assert db.execute("SELECT count(*) FROM event_participants WHERE actor_id='a'").fetchone()[0] == 2
    passed += ['duplicate_names_are_distinct', 'multiple_roles', 'fiction_context_isolation']

    transaction(lambda: (series('s1'), interpretation('i1','s1'), version('i1','s1')))
    transaction(lambda: (interpretation('i2','s1','i1',formed=2000), version('i2','s1',change='REINTERPRETATION')))
    assert db.execute("SELECT version_id FROM relationship_current WHERE judger_actor_id='a'").fetchall() == [('i2',)]
    assert db.execute("SELECT count(*) FROM relationship_history").fetchone()[0] == 2
    passed.append('revision_preserves_old_judgment')

    db.execute("INSERT INTO decisions(decision_id,event_id,decider_actor_id,recorded_at_ms,choice_text) VALUES('d1','e1','a',2000,'fixture choice')")
    db.execute("INSERT INTO decision_basis(basis_id,decision_id,basis_interpretation_id,read_at_ms,usage_status) VALUES('basis1','d1','i2',2000,'ADOPTED')")
    transaction(lambda: (interpretation('i3','s1','i2',end=3000,formed=3000), version('i3','s1',change='TIME_BOUNDARY'),
                         series('s2'), interpretation('i4','s2',start=3000,formed=3000), version('i4','s2',previous='i3',state='ENDED',change='STATE_CHANGE')))
    transaction(lambda: (series('sb','b'), interpretation('ib','sb'), version('ib','sb')))
    assert set(db.execute('SELECT judger_actor_id,relationship_status FROM relationship_current')) == {('a','ENDED'),('b','ACTIVE')}
    assert db.execute("SELECT basis_interpretation_id FROM decision_basis WHERE basis_id='basis1'").fetchone()[0] == 'i2'
    passed += ['new_stage_is_separate_from_revision', 'independent_perspectives', 'decision_pins_old_version']

    transaction(lambda: (interpretation('i4b','s2','i4',start=3000,end=4000,formed=4000), version('i4b','s2',previous='i3',state='ENDED',change='TIME_BOUNDARY'),
                         series('s3'), interpretation('i5','s3',start=4000,formed=4000), version('i5','s3',previous='i4b',change='STATE_CHANGE')))
    assert db.execute("SELECT version_id FROM relationship_current WHERE judger_actor_id='a'").fetchone()[0] == 'i5'
    passed.append('restart_preserves_ended_stage')

    transaction(lambda: (series('sj','recorder','rel-j'), interpretation('ij','sj'),
                         version('ij','sj','rel-j',mode='JOINT_EXPLICIT',confirmations=('ea','eb','a','b')),
                         db.execute("INSERT INTO evidence_refs(evidence_id,owner_interpretation_id,source_record_id,evidence_role,confirmation_actor_id) VALUES('ea','ij','ra','CONFIRMATION','a')"),
                         db.execute("INSERT INTO evidence_refs(evidence_id,owner_interpretation_id,source_record_id,evidence_role,confirmation_actor_id) VALUES('eb','ij','rb','CONFIRMATION','b')")))
    passed.append('joint_confirmation_has_two_source_speakers')

    reject('dangling_participant_rejected', lambda: db.execute("INSERT INTO event_participants(participation_id,event_id,actor_id,participation_kind,role) VALUES('bad','missing','a','PARTICIPANT','role')"))
    reject('duplicate_source_rejected', lambda: db.execute("INSERT INTO source_records(record_id,source_system,source_container_id,source_object_id,recorded_at_ms,record_kind,content_text,availability) VALUES('duplicate','fixture','conversation','ra',1000,'MESSAGE','copy','PRESENT')"))
    reject('relationship_requires_extension', lambda: (series('bad-series'), interpretation('bad-version','bad-series')))
    reject('joint_confirmation_without_sources_rejected', lambda: (series('bad-joint'), interpretation('bad-joint-v','bad-joint'), version('bad-joint-v','bad-joint',mode='JOINT_EXPLICIT')))
    reject('confirmation_source_author_mismatch', lambda: db.execute("INSERT INTO evidence_refs(evidence_id,owner_interpretation_id,source_record_id,evidence_role,confirmation_actor_id) VALUES('bad-e','ij','ra','CONFIRMATION','b')"))
    reject('multiple_evidence_owners_rejected', lambda: db.execute("INSERT INTO evidence_refs(evidence_id,owner_event_id,owner_decision_id,source_record_id,evidence_role) VALUES('bad-e2','e1','d1','ra','SUPPORTS')"))
    reject('cross_judger_stage_rejected', lambda: (series('bad-owner','b'), interpretation('bad-owner-v','bad-owner',start=5000,formed=5000), version('bad-owner-v','bad-owner',previous='i5',change='STATE_CHANGE')))
    reject('cross_series_revision_rejected', lambda: (interpretation('bad-revision','sb','i5'), version('bad-revision','sb')))
    reject('revision_cycle_rejected', lambda: db.execute("UPDATE interpretations SET previous_version_id='i3' WHERE version_id='i1'"))
    reject('series_ownership_mutation_rejected', lambda: db.execute("UPDATE interpretation_series SET judger_actor_id='b' WHERE series_id='s1'"))

    db.execute("INSERT INTO event_links VALUES('l1','e1','e2','SUBEVENT','a',NULL,'DIRECT',1000,NULL)")
    reject('subevent_cycle_rejected', lambda: db.execute("INSERT INTO event_links VALUES('l2','e2','e1','SUBEVENT','a',NULL,'DIRECT',1000,NULL)"))
    db.execute("INSERT INTO event_links VALUES('related1','e1','e2','RELATED','a',NULL,'DIRECT',1000,NULL)")
    db.execute("INSERT INTO event_links VALUES('related2','e2','e1','RELATED','a',NULL,'DIRECT',1000,NULL)")
    passed.append('related_edges_may_be_cyclic')

    db.execute("INSERT INTO executions(execution_id,event_id,decision_id,executor_actor_id,attempt_number,action_text,execution_status,recorded_at_ms) VALUES('x1','e1','d1','a',1,'fixture attempt','FAILED',2000)")
    db.execute("INSERT INTO executions(execution_id,event_id,decision_id,executor_actor_id,attempt_number,action_text,execution_status,recorded_at_ms) VALUES('x2','e1','d1','a',2,'fixture retry','COMPLETED',3000)")
    db.execute("INSERT INTO observations(observation_id,source_event_id,target_event_id,execution_id,observer_actor_id,observation_kind,content_text,recorded_at_ms,epistemic_kind) VALUES('o1','e2','e1','x2','b','FEEDBACK','later feedback',4000,'SELF_REPORT')")
    assert db.execute("SELECT count(*) FROM executions WHERE decision_id='d1'").fetchone()[0] == 2
    passed += ['retries_are_distinct', 'delayed_feedback_keeps_source_event']
    reject('wrong_execution_event_rejected', lambda: db.execute("INSERT INTO executions(execution_id,event_id,decision_id,executor_actor_id,attempt_number,action_text,execution_status,recorded_at_ms) VALUES('bad-x','e2','d1','a',3,'wrong event','COMPLETED',3000)"))

    transaction(lambda: (interpretation('i5-branch','s3',start=4000,formed=4500), version('i5-branch','s3')))
    counts = db.execute("SELECT concurrent_candidate_count FROM relationship_current WHERE relationship_id='rel' AND judger_actor_id='a'").fetchall()
    assert counts and all(row[0] == 2 for row in counts)
    passed.append('parallel_conflicts_are_not_silently_overwritten')
    assert not list(db.execute('PRAGMA foreign_key_check'))
    assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
    db.close()
    return {'sqlite_version': sqlite3.sqlite_version, 'core_table_count': len(CORE), 'total_schema_tables': len(tables),
            'passed_checks': passed, 'check_count': len(passed), 'fixture_data_written_to_device': False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('package')
    args = parser.parse_args()
    print(json.dumps(check(args.package), ensure_ascii=False))


if __name__ == '__main__':
    main()
