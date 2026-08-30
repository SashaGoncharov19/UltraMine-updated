**Ядро UltraMine - это реализация minecraft сервера на основе MinecraftForge, действительно пригодное для промышленного использования на высоконагруженных серверах с сотнями модов. В отличии от Cauldron, Не реализует Bukkit API и не поддерживает плагины.**

Нигде в этих статьях вы не найдете ни описания ядра, ни чем оно отличается от MinecraftForge. Здесь только прикладная часть: конфигурация, использование, обслуживание. Никакого маркетинга.

+ [Быстрый старт](https://github.com/4gname/UltraMine/wiki/Quickstart "Quickstart")
+ Конфигурация сервера
  + Базовая настройка сервера - [server.yml](https://github.com/4gname/UltraMine/wiki/Server.yml)
  + Настройка миров - [worlds.yml](https://github.com/4gname/UltraMine/wiki/Worlds.yml)
  + Настройка пермишанов - [Permissions](https://github.com/4gname/UltraMine/wiki/Permissions)
  + Блокировка предметов - [itemblocker.yml](https://github.com/4gname/UltraMine/wiki/Itemblocker.yml)
+ [Спавн мобов в UltraMine](https://github.com/4gname/UltraMine/wiki/Спавн-мобов-в-UltraMine "Mobs")
+ [Настройка start-файла](https://github.com/4gname/UltraMine/wiki/Launching "Launching")

Прочие ссылки:
+ [Libraries](https://github.com/4gname/UltraMine/raw/master/ultramine/libraries/libraries.zip "libraries")
+ [Исходники ядра](https://github.com/4gname/UltraMine/tree/master/ultramine/ultrasource "src")
+ [Maven репозиторий](http://maven.ultramine.ru/org/ultramine/core/ "Maven")

Документация по кодовой базе (English, для разработки/обновления ядра): [docs/README.md](docs/README.md)

### Сборки и релизы

Все бинарники собираются **автоматически на GitHub Actions** из исходников этого репозитория — никаких вручную загруженных jar-файлов. Каждый релиз содержит `SHA256SUMS.txt` и подписанную аттестацию происхождения (GitHub artifact attestations), по которой любой может убедиться, что файл собран именно этим репозиторием из конкретного коммита:

```bash
sha256sum -c SHA256SUMS.txt --ignore-missing            # целостность
gh attestation verify <файл> --repo SashaGoncharov19/UltraMine-updated   # происхождение
```

Готовый к запуску сервер — `*-server-dist.zip` в [релизах](../../releases). Подробнее: [docs/07-ci-and-releases.md](docs/07-ci-and-releases.md). Старые бинарники в `ultramine/libraries/` и `bootstrap.jar` остались от прежней схемы распространения и аттестациями не покрыты.

Известные несовместимости:

+ FastCraft 
+ ServerTools 
+ ForgeEssentials 
+ DragonAPI 
+ zzzzzcustomconfigs 
+ NEID 
