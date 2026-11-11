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

Un bouton **Inverser** permet d'obtenir le sens Arpajon → Vélizy.

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
