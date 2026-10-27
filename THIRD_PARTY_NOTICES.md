# Third-party notices

The Kotlin parser in `core/comcigan` is derived from
[hegelty/pycomcigan](https://github.com/hegelty/pycomcigan), commit
`cf738ee8945ef3c435cf1abf72e43ee90e32d1a7`.

Copyright (c) 2023 hegelty. Licensed under MIT.
The full notice is preserved in `LICENSES/pycomcigan-MIT.txt` and in the app's
Settings → Open source notices screen.

The NEIS client API design and field mapping in `core/neis` are adapted from
[kimcore/neis.kt](https://github.com/kimcore/neis.kt), commit
`555daa7d0c24fb98b45e3c2f18bfe3ffa3c11135` (MIT, Copyright (c) 2022 kimcore).
The transport was rewritten for OkHttp 4/JDK 17, with explicit API error handling,
complete pagination, cancellation, optional fields and response validation.
The full notice is in `LICENSES/neis.kt-MIT.txt` and in the app's bundled notices.

The application is licensed under MIT, with the above MIT-derived portions
retaining their notice. Kotlin, kotlinx libraries, AndroidX/Compose, Material
components and OkHttp are used under Apache-2.0; Okio is used under Apache-2.0.
Gradle wrapper scripts/JAR are distributed by Gradle under Apache-2.0.

The build downloads dependency artifacts from Google Maven and Maven Central.
Original embedded dependency notices remain in those distributions. This project
does not contain the JDK, Android SDK or Gradle distribution.
