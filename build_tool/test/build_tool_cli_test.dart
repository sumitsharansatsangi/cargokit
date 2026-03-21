import 'dart:io';

import 'package:path/path.dart' as path;
import 'package:test/test.dart';

void main() {
  group('build_tool CLI', () {
    test('gen-key prints a private and public key', () async {
      final result = await _runCli(['run', 'bin/build_tool.dart', 'gen-key']);

      expect(result.exitCode, 0);
      expect(result.stdout, contains('Private Key: '));
      expect(result.stdout, contains('Public Key: '));
    });

    test('verify-binaries succeeds for crates without precompiled config',
        () async {
      final tempDir = Directory.systemTemp.createTempSync('cli_manifest_');
      addTearDown(() => tempDir.deleteSync(recursive: true));

      File(path.join(tempDir.path, 'Cargo.toml')).writeAsStringSync('''
[package]
name = "demo"
version = "0.1.0"
edition = "2021"
''');

      final result = await _runCli([
        'run',
        'bin/build_tool.dart',
        'verify-binaries',
        '--manifest-dir=${tempDir.path}',
      ]);

      expect(result.exitCode, 0);
      expect(result.stdout, contains('Crate does not support precompiled binaries.'));
    });

    test('build-cmake reports missing environment variables clearly', () async {
      final result = await _runCli(
        ['run', 'bin/build_tool.dart', 'build-cmake'],
      );

      expect(result.exitCode, 1);
      expect(
        result.stderr,
        contains('Missing required environment variable "CARGOKIT_ROOT_PROJECT_DIR"'),
      );
    });
  });
}

Future<ProcessResult> _runCli(List<String> args) {
  return Process.run(
    Platform.resolvedExecutable,
    args,
    workingDirectory: Directory.current.path,
  );
}
