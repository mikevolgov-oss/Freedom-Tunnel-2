# ZTE MF283 read-only diagnostics

This tool collects a small set of status values from the MF283 web interface. It is intended to help distinguish LTE registration from an established mobile-data session.

## Safety

- Read-only: it makes one GET request to the router's existing status endpoint.
- It does not write settings, change APN or network mode, lock bands, update firmware, or touch IMEI.
- It deliberately does not request IMEI, IMSI, phone number, passwords, or session cookies.
- Some fields may be empty or unsupported on a particular MF283 firmware. The script does not assume MF283V firmware compatibility.

## Run

1. Connect the computer to the MF283 Wi-Fi or LAN.
2. Sign in to the router web interface, normally `http://192.168.0.1`.
3. Open Developer Tools (F12) and select **Console**.
4. Open [mf283-readonly-diagnostic.js](./mf283-readonly-diagnostic.js), copy its full contents, paste into Console, and press Enter.
5. Inspect the output under **MF283 READ-ONLY DIAGNOSTICS**. A screenshot of the output is enough for troubleshooting.

If the browser blocks pasting into the Console, do not enable paste or run unrelated commands. You can instead inspect the existing `goform_get_cmd_process` request in the Network tab.

## Interpretation

The output is diagnostic evidence, not a carrier restriction bypass. An LTE network type alone does not prove that a data session has an IP address or working routing. Empty fields do not prove a fault; command names and support vary across firmware builds.
