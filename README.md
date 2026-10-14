# ShieldGuard

Anti-freecam, anti-xray, storage-ESP bescherming en een reportsysteem voor Paper 26.2.

## Installeren

1. Zet de **PacketEvents**-plugin (Spigot/Paper-versie 2.14.0 of nieuwer) in `plugins/`.
2. Bouw ShieldGuard: `mvn package` met Java 25. De JAR staat daarna in `target/ShieldGuard-1.0.0.jar`.
3. Zet de jar in `plugins/`, start de server en pas `plugins/ShieldGuard/config.yml` aan
   (vooral `worlds:` — alleen overworld-achtige werelden!).

## Wat doet het

**Anti-freecam**: de server stuurt alleen echt ondergronds spul als deepslate/stone naar de client:
blokken die dieper liggen dan (jouw Y - 32) **én** dieper dan (terreinoppervlak - 12). Op een berg zie je dus
gewoon de echte vallei onder je; het masker volgt het terrein in plaats van een vlak stuk stone te zijn.
Ga je omlaag, dan worden de chunks om je heen automatisch opnieuw verstuurd met de echte blokken.

**Anti-xray**: volledig ingesloten ertsen worden voor de client stone/deepslate. Ertsen die aan een grot/open ruimte
grenzen zijn direct zichtbaar, ook verder weg in een grote cave. Dat is speelbaarder, maar Xray kan zulke al blootliggende
ertsen in geladen chunks aanwijzen, ook als een muur het zicht blokkeert. Raw iron-, gold- en copperblokken blijven
apart verborgen tot `reveal-radius` (standaard 12 blokken) en een vrije zichtlijn. De controle draait elke 2 ticks
(0,1 seconde); buiten het bereik wordt de nepweergave opnieuw verstuurd.

**Anti storage-ESP**: kisten, trapped chests, ender chests, barrels, hoppers, droppers, dispensers, crafters, ovens,
brewing stands, spawners en shulker boxes worden net zo behandeld (standaard zichtbaar binnen 10 blokken en in zicht).
Minecarts met kisten/hoppers/spawners en chest-boats zijn per speler onzichtbaar tot ze dichtbij en in zicht zijn.

**Beweging-anticheat** (`movement:` in config): detecteert fly (te hoog stijgen, zweven, onnatuurlijk langzaam vallen),
speed (gemiddelde snelheid over 1 seconde) en jesus (over water lopen). De server kijkt zelf naar de blokken onder de speler
en vertrouwt de onGround-vlag van de client niet. Uitzonderingen: creative/spectator, elytra, riptide, voertuigen, water, lava,
ladders, ijs, slijm, honing, spinnenwebben, potions (levitation/slow falling/jump boost), knockback/explosies en teleports.
Overtredingen geven strafpunten (VL) die vanzelf zakken. Een speler wordt alleen teruggezet als die op stabiele grond staat en het veilige checkpoint maximaal `setback-max-distance` blokken weg is. Tijdens een sprong of val wordt geen setback uitgevoerd. De hovercheck meet verstreken tijd (`hover-duration-ms`) in plaats van losse packet/move-events, zodat snel van een rand afrennen niet als zweven wordt geteld. Vanaf `kick-vl` kan nog wel een kick volgen; stel die drempel voorzichtig af.
Staff met `shieldguard.alerts` krijgt meldingen; `/shieldguard vl <speler>` toont de VL per check.

**Combat-waarschuwingen** (`combat:` in config): meldt uitzonderlijk lange melee-hit-afstanden en aanhoudend extreem hoge arm-swing-aantallen. Deze signalen zijn heuristieken: ze melden alleen aan staff en stoppen of bestraffen niemand automatisch. `shieldguard.combat.bypass` sluit staff uit.

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

Let op: met `shieldguard.bypass` zie je gewoon alles. Test de bescherming dus met een account zonder die permission.

## Afstellen

Alles staat in `config.yml`:
- `freecam-protection.surface-margin`: hoger = ondiepe grotten blijven zichtbaar, lager = ook ondiepe bases verborgen.
- `xray-protection.reveal-radius` / `storage-protection.reveal-radius`: hoe dichtbij je iets moet zijn om het te zien.
- `xray-protection.hide-exposed-ores`: `false` toont cave-exposed ertsen op renderafstand; `true` verbergt ze tot dichtbij en in zicht.
- `xray-protection.always-hidden-blocks`: blokken die ook bij `hide-exposed-ores: false` verborgen blijven, zoals raw metal blocks.
- Na installatie/update: pas deze waarden ook aan in de bestaande `plugins/ShieldGuard/config.yml`; Bukkit vervangt een bestaande configuratie niet automatisch.
- `storage-protection.hide-entities: false`: minecarts met kisten niet verbergen.
- Herlaad met `/shieldguard reload`. Voor gewijzigde taakintervallen (`reveal-interval-ticks` en `freecam-protection.refresh-interval-ticks`) is een serverherstart nodig.

## Technische basis

`pom.xml` is ingesteld op Paper API `26.2.build.129-stable`, PacketEvents 2.14.0 en Java 25. De aangeleverde basiscode was oorspronkelijk geschreven voor Paper 1.21.x. Deze aangepaste broncode is hier niet met Paper 26.2 gecompileerd of op een server getest; voer na upload naar GitHub een Maven-build uit en controleer de serverconsole bij de eerste start.

## Bekende beperkingen

- De movement-checks zijn bewust voorzichtig en vangen niet alles: geen volledige KillAura-, aim-, scaffold-, NoFall-,
  phase-, timer- of elytra-hack-detectie. De reach- en swingsignalen zijn alleen aanvullende heuristieken en vormen geen
  bewijs. Begin met een hoge `kick-vl`, bekijk staffmeldingen en stel `tolerance` en limieten voorzichtig bij.
- Geen enkele plugin kan letterlijk elke cheat detecteren; hacks kunnen onder drempels blijven of legitiem gedrag nabootsen.
  Voor bredere packet-simulation-checks kun je GrimAC naast ShieldGuard gebruiken, eerst met meldingen en handmatige controle.
- Moet je zelf spelen/testen? Staff met OP heeft `shieldguard.movement.bypass` en `shieldguard.bypass` standaard.

- Kleine reveal/remask-vertraging (standaard max ~0,1 s) bij het naderen of verlaten van erts/kisten, door `reveal-interval-ticks`.
- De freecam-chunks worden elke 2 ticks ververst (maximaal 8 chunks per speler per run); na 8 blokken stijgen kan de maskering terugschuiven.
- Een overhang of boom boven een grot telt als "oppervlak": ondiepe grotten daaronder blijven binnen de marge zichtbaar.
- Erts of kisten achter glas of bladeren worden pas getoond als de zichtlijn vrij is.
