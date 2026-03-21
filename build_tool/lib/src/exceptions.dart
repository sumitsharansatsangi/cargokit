class CargokitException implements Exception {
  CargokitException(this.message);

  final String message;

  @override
  String toString() => message;
}

class EnvironmentVariableException extends CargokitException {
  EnvironmentVariableException({
    required this.name,
    this.context,
  }) : super(_buildMessage(name: name, context: context));

  final String name;
  final String? context;

  static String _buildMessage({
    required String name,
    String? context,
  }) {
    final details = context == null ? '' : ' Required for $context.';
    return 'Missing required environment variable "$name".$details';
  }
}

class UnsupportedPlatformException extends CargokitException {
  UnsupportedPlatformException(this.details)
      : super('Unsupported or unrecognized build target. $details');

  final String details;
}

class ArtifactException extends CargokitException {
  ArtifactException(this.details)
      : super('Artifact resolution failed. $details');

  final String details;
}
