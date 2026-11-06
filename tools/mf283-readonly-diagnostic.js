/*
 * ZTE MF283 read-only diagnostics
 *
 * Run only while logged into the router web UI at http://192.168.0.1:
 * open Developer Tools -> Console and paste this entire file.
 *
 * READ-ONLY: sends one GET request to the router's existing status endpoint.
 * It does not change APN, network mode, bands, firmware, IMEI, or any settings.
 *
 * Note: command support varies by firmware. Unsupported fields may be empty.
 */
(async () => {
  const commands = [
    "modem_main_state",
    "pin_status",
    "network_type",
    "network_provider",
    "signalbar",
    "wan_ipaddr",
    "ppp_status",
    "pdp_type",
    "connectionMode",
    "dial_conn_fail_counter",
    "ppp_dial_conn_fail_counter",
    "opms_wan_mode",
    "realtime_tx_bytes",
    "realtime_rx_bytes"
  ];

  const params = new URLSearchParams({
    isTest: "false",
    multi_data: "1",
    cmd: commands.join(","),
    _: String(Date.now())
  });

  const url = "/goform/goform_get_cmd_process?" + params.toString();

  try {
    const response = await fetch(url, {
      method: "GET",
      credentials: "same-origin",
      headers: {
        "X-Requested-With": "XMLHttpRequest",
        "Accept": "application/json, text/plain, */*"
      },
      cache: "no-store"
    });

    const raw = await response.text();
    let data;
    try {
      data = JSON.parse(raw);
    } catch {
      console.error("Router returned non-JSON data. HTTP:", response.status);
      console.log(raw.slice(0, 1500));
      return;
    }

    // Deliberately omit device/user identifiers such as IMEI, IMSI, and SIM number.
    console.log("MF283 READ-ONLY DIAGNOSTICS");
    console.table(data);
    console.log("Raw result:", JSON.stringify(data, null, 2));
    console.log("HTTP status:", response.status);
    console.log("If some values are blank, this firmware may not expose those fields.");
  } catch (error) {
    console.error("Could not query router status:", error);
    console.log("Check that the router page is open and you are logged in.");
  }
})();
