# Trajet Sytadin — Vélizy ↔ Arpajon

Petite application Android (Kotlin) qui affiche le temps de trajet en voiture
entre Vélizy et Arpajon, calculé en temps réel à partir de
[sytadin.fr](https://www.sytadin.fr) (DiRIF, Île-de-France).

## Fonctionnement

L'app ouvre la page « Calculer son temps de parcours » de Sytadin
(`/gp/itineraire.action`), lit dynamiquement la liste des pôles disponibles
(avec leurs identifiants), choisit :

- **Départ** : le pôle contenant « Vélizy » (ex. `Velizy (D53)`)
- **Arrivée** : le pôle contenant « Arpajon », sinon le pôle le plus proche sur
  la N20 (`Linas (N20)` — Arpajon n'a pas de pôle propre sur Sytadin)

puis soumet le formulaire et extrait de la page résultat :

- le **temps de parcours** temps réel (ex. `35 mn`, `1h05`)
- la **fiabilité** (% de l'itinéraire couvert par des données temps réel)
- la **distance** quand Sytadin l'affiche

Les **deux sens** (Vélizy → Arpajon et Arpajon → Vélizy) sont affichés
simultanément et rafraîchis automatiquement **toutes les 5 minutes** tant que
l'application est active.

## Alerte sur seuil

L'app intègre une fonction de surveillance activable :

- l'utilisateur règle un **temps de trajet seuil** en minutes (réglage par pas
  de 5 mn, boutons − / +) ;
- un interrupteur active la **surveillance en tâche de fond** (service en
  avant-plan) : l'app continue de se mettre à jour toutes les 5 minutes et
  **notifie** dès que le temps de trajet actualisé dépasse le seuil, dans un
  sens comme dans l'autre ;
- la notification n'est envoyée qu'au franchissement du seuil (pas de
  répétition toutes les 5 minutes) ; elle repart si le temps repasse sous le
  seuil puis le redépasse.

## Chien de garde

Une seconde fonction activable, le **chien de garde**, aide à partir à temps :

- l'utilisateur choisit son **point d'arrivée** (Vélizy ou Linas/Arpajon),
  l'**heure d'arrivée souhaitée** (réglée à 10 minutes près via un sélecteur
  d'heure) et une **marge** en minutes (pas de 5 mn, boutons − / +) ;
- l'interrupteur active le chien de garde : comme pour l'alerte, la
  surveillance tourne en **tâche de fond** et vérifie le temps de trajet
  toutes les 5 minutes ;
- le temps de trajet actuel est comparé à l'heure d'arrivée voulue : dès qu'il
  ne reste plus que la marge (ou moins) avant l'heure de départ au plus tard,
  une **notification** « C'est l'heure de partir ! » est envoyée, avec le temps
  actuel et l'heure limite de départ ;
- la notification n'est envoyée qu'une fois par période critique ; elle peut
  se déclencher à nouveau si la situation redevient confortable puis se
  dégrade.

L'alerte seuil et le chien de garde peuvent être actifs simultanément ; un
seul service de fond est utilisé, arrêté dès que les deux sont désactivés (ou
via le bouton « Quitter l'application »).

## Construire l'APK

Deux options :

1. **Android Studio** : ouvrir le dossier du projet, puis
   *Build > Build APK(s)*.
2. **GitHub Actions** : pousser le projet sur un dépôt GitHub ; le workflow
   `Build APK` compile automatiquement `app-debug.apk` et le publie dans les
   *Artifacts* du run (téléchargeable depuis l'onglet **Actions**).

Ensuite, transférer le fichier `app-debug.apk` sur le téléphone et
l'installer (autoriser l'installation de sources inconnues).

## Remarques

- Les données restent la propriété de la DiRIF ; usage strictement personnel.
- Si Sytadin modifie son formulaire, l'app s'adapte en relisant les champs et
  les pôles directement depuis la page ; une liste de secours (IDs connus) est
  intégrée au cas où la liste déroulante serait chargée en JavaScript.
