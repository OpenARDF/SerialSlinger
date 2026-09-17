# Bench equipment recommendations

Prepared 2026-09-16 for Charles's existing Siglent SDS2304X, two available
SignalSlingers, and Android devices. This is a shortlist, not a purchase order
or proof that the fixture has been commissioned. Prices exclude shipping,
taxes, tariffs, and currency conversion unless the vendor explicitly includes them.

## Keep the existing scope

Use the SDS2304X over Ethernet first. Siglent's own LXI application note lists
the exact SDS2304X, including firmware 1.2.2.2 and 1.2.2.2R10, with a limited
command-set caveat. That list is historical, not a current firmware recommendation.
Verify identity, firmware, capture control, measurement queries, waveform
transfer, and screenshots on Charles's unit before assigning automated tests.
Do not assume SDS2000X Plus or HD commands work on the original X model.
[Siglent remote-control evidence](https://siglentna.com/application-note/lxi-tools/)

The first connection trial needs an Ethernet connection to the test computer's
network. No replacement scope, scope camera, or paid analysis software is
recommended before that trial. Capture transfer latency must be characterized;
instrument sampling speed does not mean continuous network streaming is possible.

## First purchases

| Item | Quantity | Price basis | Purpose |
| --- | --- | --- | --- |
| Siglent SPD3303X-E programmable supply | 1, unless an equivalent supply is already available | Allow about US$460; TEquipment lists US$459 with a US$436.05 discounted figure | Control external supply voltage and power-cycle tests; two adjustable channels support two external supply paths |
| Yepkit YKUSH 3 | 1 initially | Vendor lists EUR124.99 | Selectively disconnect USB power and data on the desktop test lane |
| RF dummy loads, protected sampling/attenuation, cables and adapters | One path per transmitter | US$100–200 planning allowance, not vendor quotation | Reuse the scope for RF presence, timing, waveform, and characterized amplitude measurements |
| Fixture controller, DC switching, watchdog and wiring | One fixture | US$50–150 planning allowance | Reset/inhibit/power control that remains effective if the host or Codex stops |
| Optical LED sensors and mounts; reuse a webcam if available | As needed | US$20–60 planning allowance | Automated blink timing and visual context |

The supply has USB/LAN remote-control capability, but its current readout is
not a sleep-current analyzer. Buying the higher-resolution SPD3303X would
still leave that measurement gap. The X-E is the proposed value choice when
a separate current profiler will be used. Its two adjustable outputs do not
independently emulate all four internal/external battery paths of two radios;
complex battery scenarios can run sequentially.
[Siglent specifications](https://siglentna.com/download/2522/?tmstv=1772776819),
[retailer price listing](https://www.tequipment.net/Siglent-pricelist/?pg=4)

The YKUSH 3 switches both VBUS and data, unlike the original power-only YKUSH.
Its manufacturer supplies Linux/Windows tooling; Mac integration is not yet
verified. Validate an adapter on the Mac or use a Linux controller before
standardizing on multiple units. It is a downstream hub, not a two-host
selector. Android USB-host control, charging, and independent switch control
require a separate topology check. A Mac cannot automatically control a hub
through an Android-owned USB connection. Wireless Android debugging can avoid
competing for the phone's USB port, but must also be commissioned.
[YKUSH 3](https://www.yepkit.com/product/300110/YKUSH3),
[manufacturer's control application](https://github.com/Yepkit/ykush)

Do not select the final RF load or attenuation by price alone: establish the
maximum RF power, duty cycle, connector, frequency, and scope input conditions.
The RF sampling path needs to protect the scope and maintain the intended load.
A scope-based measurement can establish relative changes and timing early;
absolute output power needs characterization. Defer a spectrum analyzer until
spectral purity or receiver dynamic range becomes an explicit acceptance need.

## Current measurement: choose one starting route

**Budget route: Nordic nRF-PPK2, US$107.30 listed at DigiKey.** Its specified
0.8–5 V and up-to-1 A envelope makes it a candidate for isolated low-voltage
controller/internal-battery-path tests. It is not a direct instrument for the
external nominal 12 V path. Nordic specifies 100 ksps measurement; automated
Python control exists through an unofficial IRNAS library, so qualify sample
continuity, sustained recording, and installed firmware compatibility before
using unattended results as release evidence. Avoid connecting a charger or
other source so it drives back into the profiler's output.
[Nordic electrical specifications](https://www.nordicsemi.com/Products/Development-hardware/Power-Profiler-Kit-2),
[DigiKey price](https://www.digikey.com/en/products/detail/nordic-semiconductor-asa/NRF-PPK2/13557476),
[unofficial Python interface](https://github.com/IRNAS/ppk2-api-python)

**Broader route: Joulescope JS320, US$999 listed by the manufacturer.** This is
the stronger recommendation if power-related firmware work will be frequent:
it measures voltage/current together, is isolated from USB, supports macOS,
and has manufacturer-provided Python bindings and command-line utilities.
Its specified envelope is ±15 V and ±3 A continuous, with limited pulse
capability described in its manual. Check actual external-supply maxima and
transients; nominal 12 V does not guarantee compliance with a 15 V limit.
It is an analyzer, so retain the programmable supply. Start with one and profile
one path at a time. The JS220 is discontinued and replaced by JS320; consider
a used JS220 only with a substantial discount and verified condition.
[JS320 specifications and price](https://www.joulescope.com/products/js320),
[software interfaces](https://www.joulescope.com/pages/downloads),
[JS220 discontinuation](https://www.joulescope.com/products/js220)

## Spending stages

- First verify the existing scope's LAN automation at little or no hardware cost.
- A first fixture with one supply, one USB switching hub, PPK2, RF sampling,
  basic controller/cutoff, optical sensing, and miscellaneous wiring is roughly
  US$900–1,200 before shipping/tax/import charges. This is a planning estimate,
  with the euro-priced hub rounded into an allowance, not an exchange-rate quote.
- Replacing PPK2 with JS320 adds about US$892, making the comparable fixture
  roughly US$1,800–2,100. It improves power-measurement coverage and integration.
- These totals do not include a separate Linux host, a full Mac/Android USB
  routing matrix, battery-emulation electronics, thermal equipment, or labor.

Prioritize the fixture and software over additional instruments. Defer a new
scope, premium logic analyzer, dedicated bench DMM, thermal chamber, and
spectrum analyzer until a scenario requires them. A basic fixed webcam is
optional initially; optical sensors and direct app/scope captures supply much
of the needed evidence more deterministically.

Next implementation milestone: demonstrate one complete unattended scenario
using real UI actions and an independent RF capture, then demonstrate that an
intentional failure is detected and the fixture reaches its defined final state.
Only then expand the equipment count and unattended correction loop.
