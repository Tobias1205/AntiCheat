# ShieldGuard

Anti-freecam, anti-xray, storage-ESP bescherming en een reportsysteem voor Paper 26.2.

## Installeren

1. Zet [PacketEvents](https://github.com/retrooper/packetevents/releases) (Spigot/Paper-versie 2.14.0 of nieuwer) én [GrimAC](https://modrinth.com/plugin/grimac) in `plugins/`. Gebruik de GrimAC-build die Paper 26.2 ondersteunt; ShieldGuard vereist GrimAC bij het starten.
2. Bouw ShieldGuard: `mvn package` met Java 25. De JAR staat daarna in `target/ShieldGuard-1.0.0.jar`.
3. Zet de ShieldGuard-jar ook in `plugins/`, start de server en pas `plugins/ShieldGuard/config.yml` aan
   (vooral `worlds:` — alleen overworld-achtige werelden!).

## Wat doet het

**Anti-freecam**: de server stuurt alleen echt ondergronds spul als deepslate/stone naar de client:
blokken die dieper liggen dan (jouw Y - 32) **én** dieper dan (terreinoppervlak - 12). Op een berg zie je dus
gewoon de echte vallei onder je; het masker volgt het terrein in plaats van een vlak stuk stone te zijn.
Ga je omlaag, dan worden de chunks om je heen automatisch opnieuw verstuurd met de echte blokken.

**Anti-xray**: ertsen, ook ertsen die aan grotten grenzen, blijven voor de client stone/deepslate tot de speler ze
binnen `reveal-radius` (standaard 160 blokken) én met een vrije zichtlijn kan zien. Erts achter een muur wordt dus niet
aan Xray prijsgegeven. Raw iron-, gold- en copperblokken blijven apart verborgen tot ze dichtbij en zichtbaar zijn.
De plugin houdt ertsposities uit verstuurde chunks bij in plaats van alle blokken binnen die grote straal af te lopen.
Elke 10 ticks (0,5 seconde) controleert hij afstand, open blootstelling en zichtlijn; buiten bereik of zichtlijn wordt
de nepweergave opnieuw verstuurd. De effectieve afstand kan lager zijn als de server- of client-renderafstand lager is.

**Anti storage-ESP**: kisten, trapped chests, ender chests, barrels, hoppers, droppers, dispensers, crafters, ovens,
brewing stands, spawners en shulker boxes worden net zo behandeld (standaard zichtbaar binnen 10 blokken en in zicht).
Minecarts met kisten/hoppers/spawners en chest-boats zijn per speler onzichtbaar tot ze dichtbij en in zicht zijn.

**GrimAC-integratie**: GrimAC voert de diepere packet- en bewegingschecks uit; ShieldGuard ontvangt Grim-vlaggen via Grim's officiële API en stuurt ze als staffmeldingen door. GrimAC moet als aparte plugin in `plugins/` staan; de Grim-code wordt niet in ShieldGuard's jar gebundeld. Grim zelf bepaalt detecties en straffen via zijn configuratie; ShieldGuard annuleert Grim-vlaggen niet. Controleer Grim's configuratie en test eerst met staffmeldingen voordat je straffen aanscherpt. De lokale eenvoudige movement-checks staan standaard uit om dubbele setbacks te voorkomen; zet `movement.enabled: true` alleen als je beide systemen wilt laten controleren.

**Eigen beweging-anticheat** (`movement:` in config): optionele, eenvoudigere checks voor fly (te hoog stijgen, zweven, onnatuurlijk langzaam vallen),
speed (gemiddelde snelheid over 1 seconde) en jesus (over water lopen). De server kijkt zelf naar de blokken onder de speler
en vertrouwt de onGround-vlag van de client niet. Uitzonderingen: creative/spectator, elytra, riptide, voertuigen, water, lava,
ladders, ijs, slijm, honing, spinnenwebben, potions (levitation/slow falling/jump boost), knockback/explosies en teleports.
Overtredingen geven strafpunten (VL) die vanzelf zakken. Een speler wordt alleen teruggezet als die op stabiele grond staat en het veilige checkpoint maximaal `setback-max-distance` blokken weg is. Tijdens een sprong of val wordt geen setback uitgevoerd. De hovercheck meet verstreken tijd (`hover-duration-ms`) in plaats van losse packet/move-events, zodat snel van een rand afrennen niet als zweven wordt geteld. Vanaf `kick-vl` kan nog wel een kick volgen; stel die drempel voorzichtig af.
Staff met `shieldguard.alerts` krijgt meldingen; `/shieldguard vl <speler>` toont de VL per check.

**Combat-waarschuwingen** (`combat:` in config): meldt uitzonderlijk lange melee-hit-afstanden en aanhoudend extreem hoge arm-swing-aantallen. Deze signalen zijn heuristieken: ze melden alleen aan staff en stoppen of bestraffen niemand automatisch. `shieldguard.combat.bypass` sluit staff uit.

**Bouwactie-waarschuwingen** (`actions:` in config): signaleert herhaald blokken plaatsen rond/onder de voeten (scaffold-patroon) en extreem snel plaatsen. Dit zijn zwakke signalen die ook bij legitiem snel bouwen kunnen voorkomen; ze waarschuwen alleen staff en leiden nooit automatisch tot een setback, kick of ban. `shieldguard.action.bypass` sluit staff uit.

**Reports**
- `/report <speler> <reden>` — cooldown, geen dubbele reports, tab-completion op redenen
- `/reports` — GUI met open reports (linksklik = teleporteren, rechtsklik = afsluiten)
- `/reports list | view <id> | tp <id> | close <id> [notitie] | reload`
- Staff met `shieldguard.reports.notify` krijgt een klikbare melding; optioneel ook een Discord-webhook
- Extra waarschuwing zodra een speler 3 (instelbaar) open reports heeft

## Controleren of het werkt

Typ `/shieldguard status` in het spel. Het laat zien of jouw wereld beschermd is, of jij de bypass-permission
hebt (OPs hebben die standaard, dan zie je alles!), je freecam-grens, en hoeveel chunks/ores/kisten verborgen zijn.
Fouten in de packet-code staan nu ook in de serverconsole.

## Permissions

| Permission | Standaard | Doel |
|---|---|---|
| `shieldguard.report` | iedereen | reports indienen |
| `shieldguard.reports.manage` | op | reports beheren |
| `shieldguard.reports.notify` | op | meldingen ontvangen |
| `shieldguard.bypass` | op | niet gemaskeerd worden |
| `shieldguard.admin` | op | `/shieldguard status`, `reload` en `vl` |
| `shieldguard.alerts` | op | meldingen van movement-hacks ontvangen |
| `shieldguard.movement.bypass` | op | niet gecontroleerd worden op fly/speed |
| `shieldguard.combat.bypass` | op | geen combatheuristiek-meldingen ontvangen |
| `shieldguard.action.bypass` | op | geen bouwactie-heuristiek-meldingen ontvangen |

Let op: met `shieldguard.bypass` zie je gewoon alles. Test de bescherming dus met een account zonder die permission.

## Afstellen

Alles staat in `config.yml`:
- `freecam-protection.surface-margin`: hoger = ondiepe grotten blijven zichtbaar, lager = ook ondiepe bases verborgen.
- `xray-protection.reveal-radius` / `storage-protection.reveal-radius`: hoe dichtbij je iets moet zijn om het te zien.
- `xray-protection.hide-exposed-ores`: laat dit op `true` staan om exposed ertsen alleen dichtbij en met zichtlijn te tonen.
- `xray-protection.always-hidden-blocks`: blokken die ook bij `hide-exposed-ores: false` verborgen blijven, zoals raw metal blocks.
- `storage-protection.hide-entities: false`: minecarts met kisten niet verbergen.
- Na installatie/update: pas de bestaande `plugins/ShieldGuard/config.yml` aan; Bukkit vervangt die niet automatisch. Zet `hide-exposed-ores: true` en `reveal-radius: 160`.
- Herlaad met `/shieldguard reload`. Voor gewijzigde taakintervallen (`reveal-interval-ticks`, `reveal-scan-cooldown-ticks` en `freecam-protection.refresh-interval-ticks`) is een serverherstart nodig.

## Technische basis

`pom.xml` is ingesteld op Paper API `26.2.build.129-stable`, PacketEvents 2.14.0, GrimAPI 1.6.0.12 en Java 25. GrimAC is een aparte vereiste plugin en wordt niet in de ShieldGuard-jar samengevoegd. Deze aangepaste broncode is hier niet met Paper 26.2 gecompileerd of op een server getest; voer na upload naar GitHub een Maven-build uit en controleer de serverconsole bij de eerste start.

## Bekende beperkingen

- De movement-checks zijn bewust voorzichtig en vangen niet alles: geen volledige KillAura-, aim-, NoFall-,
  phase-, timer- of elytra-hack-detectie. Scaffold- en snel-plaatsenmeldingen zijn heuristieken, geen bewijs. Reach-, swing- en bouwsignalen zijn alleen aanvullend en vormen geen
  bewijs. Begin met een hoge `kick-vl`, bekijk staffmeldingen en stel `tolerance` en limieten voorzichtig bij.
- Geen enkele plugin kan letterlijk elke cheat detecteren; hacks kunnen onder drempels blijven of legitiem gedrag nabootsen. GrimAC's meldingen moeten eerst op jouw server worden gecontroleerd voordat je straffen aanscherpt.
- Moet je zelf spelen/testen? Staff met OP heeft `shieldguard.movement.bypass` en `shieldguard.bypass` standaard.

- Kleine reveal/remask-vertraging (standaard max ~0,1 s) bij het naderen of verlaten van erts/kisten, door `reveal-interval-ticks`.
- De freecam-chunks worden maximaal vier per tick ververst, met de dichtstbijzijnde chunks eerst. Een nieuwe spelerpositie vervangt een verouderde wachtrij, zodat actuele chunks niet achter verre chunks blijven wachten. Dit vult grotten sneller, met iets meer chunkverkeer tijdens het verversen.
- Een overhang of boom boven een grot telt als "oppervlak": ondiepe grotten daaronder blijven binnen de marge zichtbaar.
- Erts of kisten achter glas of bladeren worden pas getoond als de zichtlijn vrij is.
