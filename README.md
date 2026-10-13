# MinecraftGuard

Paper 26.2 plugin met een `/report`-menu en voorzichtige bewegingsmeldingen voor staff.

## Bouwen

Vereist Java 25 en Maven. Voer in de hoofdmap uit:

```text
mvn package
```

De plugin verschijnt als `target/minecraftguard-0.1.0.jar`. Plaats die JAR in de map `plugins/` van je Paper-server en herstart de server.

## Commando's en permissies

- `/report` opent het report-menu.
- `/guard reload` herlaadt de pluginconfiguratie.
- `minecraftguard.report` staat standaard aan voor iedereen.
- `minecraftguard.alerts` laat staff report- en bewegingsmeldingen ontvangen; standaard voor operators.
- `minecraftguard.admin` geeft toegang tot `/guard`; standaard voor operators.
- `minecraftguard.bypass` slaat bewegingsmeldingen over; standaard voor niemand.

Bewegingsmeldingen zijn indicaties en kunnen fout-positieven bevatten. De plugin bant of kikt niet automatisch. De ingebouwde Paper Anti-Xray moet apart in de Paper-wereldconfiguratie worden ingeschakeld; zie de [Paper-handleiding](https://docs.papermc.io/paper/anti-xray/). Anti-Xray camoufleert vooral ertsen en verbergt geen bases of kisten. Freecam is niet betrouwbaar rechtstreeks vanaf de server te detecteren.

Deze repository bevat alleen `src/`, `pom.xml` en `README.md`. GitHub bouwt de JAR niet vanzelf na een upload; voer `mvn package` uit in een Maven-omgeving om de JAR te maken.
