import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  late String source;

  setUpAll(() {
    source = File('android/app/src/main/kotlin/deckers/thibault/aves/utils/MimeTypes.kt').readAsStringSync();
  });

  test('extensionFor prefers the source filename over a video/mp2t MIME mapping', () {
    final fromSource = source.indexOf('val fromSource = normalizeExtension(defaultExtension)');
    final earlyReturn = source.indexOf('if (fromSource != null) return fromSource');
    final mimeFallback = source.indexOf('MP2T, MP2TS -> ".m2ts"');
    expect(fromSource, greaterThanOrEqualTo(0));
    expect(earlyReturn, greaterThan(fromSource));
    expect(mimeFallback, greaterThan(earlyReturn));
  });
}
