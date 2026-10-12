# ShieldGuard

Anti-freecam, anti-xray en een reportsysteem voor Paper 1.21.x.

## Installeren

1. Zet de **PacketEvents**-plugin (spigot-versie, 2.7.0 of nieuwer) in `plugins/`.
2. Bouw ShieldGuard: `mvn package` (Java 21). De jar staat daarna in `target/ShieldGuard-1.0.0.jar`.
3. Zet de jar in `plugins/`, start de server en pas `plugins/ShieldGuard/config.yml` aan
   (vooral `worlds:` — alleen overworld-achtige werelden!).

## Wat doet het

**Anti-freecam**: de server stuurt alles dat dieper ligt dan (jouw Y - 32) als deepslate (onder Y=0)
of stone (boven Y=0) naar de client. Ook kisten/spawners daaronder worden niet verstuurd.
Gaat een speler omlaag, dan worden de chunks om hem heen automatisch opnieuw verstuurd met de echte blokken.

**Anti-xray**: erts dat nergens aan lucht, water of lava grenst wordt als stone/deepslate verstuurd.
Graaf je of ontploft er iets ernaast, dan wordt het echte erts getoond.
Erts op de rand van een chunk blijft altijd zichtbaar (kleine, bewuste beperking).

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
