# OTP Forwarder (Android)

A local Android app that selectively forwards incoming SMS OTP messages to a configured recipient.

## Safety model
- Forwarding is OFF by default.
- A sender allowlist is mandatory.
- At least one include keyword is mandatory.
- Exclude keywords always take precedence.
- Settings are encrypted with an AES key held in Android Keystore.
- SMS message bodies are not persisted in the app's forwarding history.
- No SMS content or settings are sent to a cloud service.
- Forwarding history records sender, time and whether the SMS was submitted to Android's SMS service. It does not confirm delivery to the recipient.

## Setup
1. Install the APK on the Android phone receiving the source OTPs.
2. Grant Receive SMS and Send SMS permissions.
3. Enter the recipient number with country code.
4. Add exact sender IDs/numbers to Allowed senders.
5. Add an include keyword such as `OTP` or `verification code`.
6. Save settings, then switch forwarding ON.

Test first with a non-sensitive SMS from an allowlisted test sender. Android/OEM battery controls may affect background delivery. SMS charges may apply.

This project is intended for personal, consent-based use. OTPs are authentication credentials; only forward messages you are authorised to share.
