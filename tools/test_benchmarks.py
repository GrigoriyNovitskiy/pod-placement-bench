import csv
import importlib.util
import json
import os
import signal
import subprocess
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    sys.modules[name] = value
    spec.loader.exec_module(value)
    return value


runner = module('benchmark_runner', 'run-benchmarks.py')
report = module('benchmark_report', 'render-benchmarks.py')


def manifest():
    return {'format_version': 1,
            'controls': {'bulk_sizes': [1, 100], 'probe_cap': 0, 'catalogue_limit': 32,
                         'catalogue_weights': 'sampled', 'gpu_model': 'devices20', 'max_operations': 0},
            'datasets': [{'cloud': 'dc', 'snapshot_sha256': 'snapshot', 'operations_sha256': 'operations'}]}


def cell(alias='fitcount', algorithm=None, bulk=100, total_ms=80, attempts=16):
    algorithm = algorithm or alias
    data = {'format_version': 2, 'algorithm': algorithm, 'algorithm_parameters': {}, 'complete': True,
            'snapshot_sha256': 'snapshot', 'operations_sha256': 'operations', 'bulk_size': bulk,
            'max_operations': 0, 'probe_cap': 0, 'catalogue_limit': 32, 'catalogue_weights': 'sampled',
            'gpu_model': 'devices20', 'scenario_digest': f'scenario-{bulk}',
            'source_operations_digest': 'source', 'normalization': 'joint-phases-v1',
            'announcement': 'single-v1' if bulk == 1 else 'service-bursts-v2',
            'catalogue_checksum': 'catalogue', 'probe_checksum': 'probes', 'probe_count': 0,
            'catalogue_units': 16, 'processed_operations': 32, 'normalized_operations': 32,
            'creates': {'attempted_units': attempts, 'attempted_resources': attempts * 2,
                        'admitted_units': min(10, attempts), 'admitted_resources': min(15, attempts * 2)},
            'timing_ms': {'schedule_total': total_ms}, 'peak_active_units': 4, 'peak_active_resources': 6,
            'logical_resources': {'requested': {'1': 1000.0},
                                  'peak_active': {'1': 100, '12': 8, '2': 1048576,
                                                  '5': 1024, '7': 2048, '9': 3072, '11': 4}},
            'algorithm_counters': {}}
    return {'cloud': 'dc', 'bulk_size': bulk, 'alias': alias,
            'mode': 'relocation' if algorithm in report.RELOCATORS else 'placement',
            'settings': {'id': algorithm, 'parameters': {}}, 'rollout_prior': None,
            'status': 'SUCCESS', 'data': data}


class BenchmarkToolsTest(unittest.TestCase):
    def test_amortized_average_peaks_and_failed_rows(self):
        base = cell()
        failed = cell('broken')
        failed.update(status='TIMEOUT', data=None)
        table = report.build_tables(manifest(), [failed, base])[0]
        row = table['rows'][0]
        self.assertEqual(5, row['avg_ms'])
        self.assertEqual((10, 15, 4, 6), tuple(row[k] for k in
                         ['placed_requests', 'placed_members', 'peak_requests', 'peak_members']))
        self.assertEqual((100, 8, 1, 1, 2, 3, 4), tuple(row[k] for k in
                         ['cpu_prod', 'cpu_nonprod', 'ram_tib', 'nvme_tib', 'ssd_tib', 'hdd_tib', 'gpu']))
        self.assertEqual('TIMEOUT', table['rows'][1]['status'])
        self.assertIsNone(table['rows'][1]['placed_requests'])
        self.assertTrue(all(not value for value in table['rows'][1]['cells'][1:]))

    def test_explicit_cross_batch_baseline_and_prefix(self):
        config = manifest()
        config['controls']['max_operations'] = 3
        rows = [cell(bulk=1, total_ms=6, attempts=3), cell(total_ms=9, attempts=3),
                cell('geometric-best-fit', total_ms=3, attempts=3)]
        for row in rows:
            row['data'].update(complete=False, max_operations=3, processed_operations=3)
        rows[1]['data']['logical_resources']['requested']['1'] = 2000.0
        rows[2]['data']['logical_resources']['requested']['1'] = 2000.0
        same = report.build_tables(config, rows)[1]
        cross = report.build_tables(config, rows, baseline_bulk_size=1)[1]
        same_row = next(row for row in same['rows'] if row['alias'] == 'geometric-best-fit')
        cross_row = next(row for row in cross['rows'] if row['alias'] == 'geometric-best-fit')
        self.assertAlmostEqual(-100 * 2 / 3, same_row['delta_avg_pct'])
        self.assertEqual(-50, cross_row['delta_avg_pct'])
        self.assertIn('B1', cross['headers'][2])
        self.assertIn('префикс 3', cross['title'])
        self.assertTrue(runner.replay_complete(rows[0]['data'], config['controls']))
        rows[0]['data']['processed_operations'] = 2
        self.assertFalse(runner.replay_complete(rows[0]['data'], config['controls']))
        with self.assertRaises(ValueError):
            report.build_tables(config, rows)

    def test_different_algorithms_can_use_different_models(self):
        first = cell('catboost-topn-fitcount')
        second = cell('catboost-rollout-topn-fitcount')
        first['data'].update(model_sha256='fitcount-model', model_feature_schema='features')
        second['data'].update(model_sha256='rollout-model', model_feature_schema='features')
        tables = report.build_tables(manifest(), [cell(), first, second])
        self.assertEqual(3, len(tables[0]['rows']))
        changed = cell('other-alias', algorithm='catboost-topn-fitcount')
        changed['data'].update(model_sha256='changed-model', model_feature_schema='features')
        with self.assertRaises(ValueError):
            report.build_tables(manifest(), [first, changed])

    def test_maintenance_is_separate_and_charged_once(self):
        planner = cell('yp-planner', total_ms=32)
        planner['data']['algorithm_counters']['yp_maintenance_nanos'] = 16000000
        tables = report.build_tables(manifest(), [cell(), cell('yp-light'), planner])
        self.assertEqual(['placement', 'relocation'], [table['mode'] for table in tables])
        row = next(row for row in tables[1]['rows'] if row['alias'] == 'yp-planner')
        self.assertEqual(3, row['avg_ms'])
        self.assertEqual(1, row['maintenance_ms'])
        self.assertEqual(3, len(tables[1]['rows']))

    def test_missing_peak_map_is_an_error_and_sparse_empty_is_valid(self):
        row = cell()
        row['data']['logical_resources']['peak_active'] = {}
        self.assertEqual(0, report.build_tables(manifest(), [row])[0]['rows'][0]['ram_tib'])
        del row['data']['logical_resources']['peak_active']
        with self.assertRaises((KeyError, ValueError)):
            report.build_tables(manifest(), [row])

    def test_markdown_and_numeric_csv_need_no_pillow(self):
        tables = report.build_tables(manifest(), [cell()])
        with tempfile.TemporaryDirectory() as temporary, patch.dict(sys.modules, {'PIL': None}):
            paths = report.write_tables(tables, Path(temporary), ['markdown', 'csv'])
            markdown = next(path for path in paths if path.suffix == '.md').read_text()
            self.assertIn('DC', markdown)
            self.assertIn('B100', markdown)
            with next(path for path in paths if path.suffix == '.csv').open() as source:
                row = next(csv.DictReader(source))
            self.assertEqual(5, float(row['avg_ms']))
            self.assertEqual(1, float(row['ram_tib']))
            self.assertEqual(10, int(row['placed_requests']))

    def test_named_variants_and_duplicate_aliases(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'algorithms.json'
            path.write_text(json.dumps({'plain': {'id': 'geometric-portfolio-mean-bulk-fitcount'},
                                        'filtered': {'id': 'geometric-portfolio-mean-bulk-fitcount',
                                                     'parameters': {'randomPrefilter': True}}}))
            variants = runner.load_algorithms(None, path)
            self.assertEqual(['plain', 'filtered'], [value['alias'] for value in variants])
            path.write_text('{"same":{"id":"fitcount"},"same":{"id":"mean-fitcount"}}')
            with self.assertRaises(ValueError):
                runner.load_algorithms(None, path)
        with self.assertRaises(ValueError):
            runner.load_algorithms(['fitcount', 'FitCount'], None)

    def test_exited_parent_does_not_leave_a_running_solver(self):
        child = """import sys,time
from pathlib import Path
p = Path(sys.argv[1])
for n in range(1000):
    p.write_text(str(n))
    time.sleep(0.02)
"""
        parent = """import os,subprocess,sys,time
from pathlib import Path
Path(sys.argv[2]).write_text(str(os.getpgrp()))
subprocess.Popen([sys.executable, '-c', sys.argv[4], sys.argv[1]])
while not Path(sys.argv[1]).exists():
    time.sleep(0.01)
sys.exit(int(sys.argv[3]))
"""
        for code in (0, 7):
            with self.subTest(exit_code=code), tempfile.TemporaryDirectory() as temporary:
                heartbeat = Path(temporary) / 'heartbeat'
                group = Path(temporary) / 'group'
                try:
                    result = runner.invoke([sys.executable, '-c', parent, str(heartbeat), str(group),
                                            str(code), child], subprocess.DEVNULL, subprocess.DEVNULL, timeout=10)
                    self.assertEqual(code, result)
                    before = heartbeat.read_bytes()
                    time.sleep(0.15)
                    self.assertEqual(before, heartbeat.read_bytes())
                finally:
                    if group.exists():
                        runner.signal_process_group(int(group.read_text()), signal.SIGKILL)

    def test_darwin_permission_error_requires_an_empty_or_zombie_group(self):
        with patch.object(runner.sys, 'platform', 'darwin'), \
             patch.object(runner.os, 'killpg', side_effect=PermissionError('denied')), \
             patch.object(runner.subprocess, 'run') as ps:
            for states in ('', '123 Z+\n456 S\n'):
                ps.return_value.stdout = states
                runner.signal_process_group(123, signal.SIGKILL)
            ps.return_value.stdout = '123 S\n'
            with self.assertRaises(PermissionError):
                runner.signal_process_group(123, signal.SIGKILL)
            ps.return_value.stdout = 'invalid\n'
            with self.assertRaises(ValueError):
                runner.signal_process_group(123, signal.SIGKILL)

    def test_interrupted_cleanup_still_kills_the_process_group(self):
        process = Mock(pid=123)
        process.wait.side_effect = [KeyboardInterrupt(), 0]
        with patch.object(runner.os, 'killpg') as kill:
            with self.assertRaises(KeyboardInterrupt):
                runner.stop_process(process)
        kill.assert_any_call(123, signal.SIGKILL)

    def test_dataset_order_and_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for cloud in ['dc', 'sc']:
                path = root / cloud / 'mixed-v2'
                path.mkdir(parents=True)
                (path / 'snapshot.pb').write_bytes(b'snapshot')
                (path / 'operations.pb').write_bytes(b'operations')
            datasets = runner.resolve_datasets(root, ['sc', 'dc'], 'mixed-v2')
            self.assertEqual(['sc', 'dc'], [value['cloud'] for value in datasets])
            self.assertEqual(runner.sha256(root / 'dc/mixed-v2/snapshot.pb'), datasets[1]['snapshot_sha256'])
            with self.assertRaises(ValueError):
                runner.resolve_datasets(root, ['dc', 'DC'], 'mixed-v2')


if __name__ == '__main__':
    unittest.main()
