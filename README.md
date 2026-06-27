# Ciao Beeline Test

Pre-test per usare il Fossil Carlyle come display remoto stile Beeline.

## Architettura

- **Telefono Android**: GPS, live routing online con OpenRouteService, ricalcolo, invio dati al Carlyle.
- **Fossil Carlyle / Wear OS**: display remoto con linea bianca, freccia, distanza e velocità.

## Prima prova

1. Crea un repository GitHub chiamato `ciao-beeline-test`.
2. Carica tutti questi file nel repository.
3. Vai su **Actions** > **Build APKs** > **Run workflow**.
4. Scarica gli artifact:
   - `ciao-beeline-mobile-debug-apk`
   - `ciao-beeline-wear-debug-apk`
5. Installa l'APK mobile sul telefono.
6. Installa l'APK wear sul Carlyle con Bugjaeger.
7. Apri l'app sul Carlyle.
8. Apri l'app sul telefono e premi **Invia demo al Carlyle**.

## Live routing

Per usare il routing reale serve una API key OpenRouteService.

Nell'app telefono inserisci:

- API key OpenRouteService
- latitudine destinazione
- longitudine destinazione

Poi premi **Start live routing**.

La prima versione usa il profilo `driving-car`. Più avanti potremo aggiungere profili diversi o una logica più adatta al Ciao.

## Note importanti

- Il Carlyle usa solo la grafica: il GPS è quello dello smartphone.
- Le app mobile e wear hanno lo stesso `applicationId`, necessario per Wear OS Data Layer.
- È una V0.1: routing, svolte e linea sono già impostati, ma andranno raffinati su strada.
