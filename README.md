# ShieldGuard

Anti-freecam, anti-xray en een reportsysteem voor Paper 1.21.x.

## Installeren

1. Zet de **PacketEvents**-plugin (spigot-versie, 2.7.0 of nieuwer) in `plugins/`.
2. Bouw ShieldGuard: `mvn package` (Java 21). De jar staat daarna in `target/ShieldGuard-1.0.0.jar`.
3. Zet de jar in `plugins/`, start de server en pas `plugins/ShieldGuard/config.yml` aan
   (vooral `worlds:` — alleen overworld-achtige werelden!).

## Wat doet het

**Anti-freecam**: de server stuurt alleen echt ondergronds spul als deepslate/stone naar de client:
blokken die dieper liggen dan (jouw Y - 32) **én** dieper dan (terreinoppervlak - 12). Op een berg zie je dus
gewoon de echte vallei onder je; het masker volgt het terrein in plaats van een vlak stuk stone te zijn.
Ga je omlaag, dan worden de chunks om je heen automatisch opnieuw verstuurd met de echte blokken.

**Anti-xray**: ALLE erts (ook in grotten) is voor de client stone/deepslate. Erts wordt pas getoond als je er
binnen `reveal-radius` (standaard 6 blokken) bent en er een vrije zichtlijn naartoe is. Door muren heen zien kan dus niet.

**Anti storage-ESP**: kisten, trapped chests, ender chests, barrels, hoppers, droppers, dispensers, crafters, ovens,
brewing stands, spawners en shulker boxes worden net zo behandeld (standaard zichtbaar binnen 10 blokken en in zicht).
Minecarts met kisten/hoppers/spawners en chest-boats zijn per speler onzichtbaar tot ze dichtbij en in zicht zijn.

**Reports**
- `/report <speler> <reden>` — cooldown, geen dubbele reports, tab-completion op redenen
- `/reports` — GUI met open reports (linksklik = teleporteren, rechtsklik = afsluiten)
- `/reports list | view <id> | tp <id> | close <id> [notitie] | reload`
- Staff met `shieldguard.reports.notify` krijgt een klikbare melding; optioneel ook een Discord-webhook
- Extra waarschuwing zodra een speler 3 (instelbaar) open reports heeft

## Permissions

| Permission | Standaard | Doel |
|---|---|---|
| `shieldguard.report` | iedereen | reports indienen |
| `shieldguard.reports.manage` | op | reports beheren |
| `shieldguard.reports.notify` | op | meldingen ontvangen |
| `shieldguard.bypass` | op | niet gemaskeerd worden |

Let op: met `shieldguard.bypass` zie je gewoon alles. Test de bescherming dus met een account zonder die permission.

## Afstellen

Alles staat in `config.yml`:
- `freecam-protection.surface-margin`: hoger = ondiepe grotten blijven zichtbaar, lager = ook ondiepe bases verborgen.
- `xray-protection.reveal-radius` / `storage-protection.reveal-radius`: hoe dichtbij je iets moet zijn om het te zien.
- `xray-protection.hide-exposed-ores: false`: erts in grotten blijft dan gewoon zichtbaar (alleen ingesloten erts verborgen).
- `storage-protection.hide-entities: false`: minecarts met kisten niet verbergen.
- Na `/reports reload` worden de instellingen herladen (behalve `reveal-interval-ticks`: daarvoor even herstarten).

## Bekende beperkingen

- Kleine reveal-vertraging (max ~0,25 s) bij het naderen van erts/kisten, door `reveal-interval-ticks`.
- Een overhang of boom boven een grot telt als "oppervlak": ondiepe grotten daaronder blijven binnen de marge zichtbaar.
- Erts of kisten achter glas of bladeren worden pas getoond als de zichtlijn vrij is.
