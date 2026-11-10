# vecDB

Учебная векторная база данных на Java.

## Статус


| Этап | Описание                                                 | Статус |
| ---- | -------------------------------------------------------- | ------ |
| 1    | Интерфейсы и скелет                                      | ✅      |
| 2    | In-memory реализация, персистентность, локальная консоль | ✅      |
| 3    | REST-сервер, OpenAPI/Swagger, Docker                     | ✅      |
| 4    | Репликация                                               | ✅      |
| 5    | Шардирование                                             | ⬜      |


## Требования

- Java 21 — для локальной сборки и запуска
- Docker с Docker Compose — для запуска в контейнере
- Доступ к Maven Central и Microsoft Container Registry — для первой сборки

## Сборка

### Windows:

```bash
mvn clean compile
mvn test
```

### MacOS:

```bash
chmod +x mvnw   # если ещё не делали
bash mvnw clean compile
bash mvnw test
```



## Запуск локальной БД

После `compile` классы лежат в `target/classes`:

```bash
java -cp target/classes com.db.Main                          # данные в ./data, автосохранение раз в 5 сек
java -cp target/classes com.db.Main --data ./mydata --save-interval 10
java -cp target/classes com.db.Main --no-autosave            # только SAVE вручную и при выходе
java -cp target/classes com.db.Main < script.txt             # выполнить команды из файла
java -cp target/classes com.db.Main --role master --data ./data-master --replication-port 4700 --no-autosave
java -cp target/classes com.db.Main --role replica --data ./data-replica --master-host 127.0.0.1 --master-port 4700 --replication-delay-ms 500 --no-autosave
```

Пример сессии:

```
vecdb> CREATE_COLLECTION docs 3 COSINE
docs (dimension=3, metric=COSINE, maxVectors=unlimited)
vecdb> PUT docs a 1 0 0 первый документ
OK
vecdb> PUT docs c 0.9 0.1 0
OK
vecdb> SEARCH docs 1 0 0 2
1) distance=0.000000  id=a vector=[1.0, 0.0, 0.0] data="первый документ"
2) distance=0.006116  id=c vector=[0.9, 0.1, 0.0]
vecdb> STATS
engine=in-memory/brute-force collections=1 vectors=2 puts=2 searches=1 hitRatio=0.00 evicted=0 lastSave=never (0 ms)
  docs: dimension=3 metric=COSINE vectors=2 puts=2 searches=1 hits=0 misses=0 hitRatio=0.00 evicted=0
vecdb> EXIT
bye
```

При выходе (`EXIT`, конец ввода или Ctrl+C) изменения сохраняются на диск; при следующем запуске коллекции загружаются автоматически.

### Команды


| Команда                                                                      | Описание                                                                           |
| ---------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `CREATE_COLLECTION <name> <dim> [COSINE\|EUCLIDEAN\|DOT_PRODUCT] [maxVectors]` | Создать коллекцию                                                                  |
| `DROP_COLLECTION <name>`                                                     | Удалить коллекцию вместе с файлами                                                 |
| `LIST_COLLECTIONS`, `DESCRIBE <name>`                                        | Список коллекций / схема коллекции                                                 |
| `PUT <coll> <id> <v1> … <vN> [payload…]`                                     | Вставить/перезаписать; N = размерность коллекции, остаток строки — payload (UTF-8) |
| `GET <coll> <id>`, `EXISTS <coll> <id>`, `DELETE <coll> <id>`                | Операции по id                                                                     |
| `SIZE <coll>`, `CLEAR <coll>`                                                | Размер / очистка коллекции                                                         |
| `SEARCH <coll> <v1> … <vN> <k>`                                              | k ближайших соседей                                                                |
| `STATS [coll]`                                                               | Статистика БД или коллекции                                                        |
| `SAVE`, `LOAD`                                                               | Сохранить изменения / перечитать с диска (несохранённое теряется)                  |
| `STATUS`                                                                     | Роль узла, LSN и лаг репликации                                                    |
| `WAL DUMP [N]`                                                               | Последние N записей WAL в JSON (по умолчанию 20)                                   |
| `PROMOTE`                                                                    | Ведомый становится ведущим (ручной failover)                                       |
| `HELP`, `EXIT`                                                               | Справка / выход                                                                    |


Регистр команд не важен, строки с `#` — комментарии.

## REST-сервер (этап 3)

Сервер запускается на порту `2222` и использует реализацию `IVectorDB` из второй лабораторной:

```bash
bash mvnw spring-boot:run
```

После сборки сервер можно запустить как исполняемый JAR:

```bash
bash mvnw clean package
java -jar target/lab-db-0.0.1-SNAPSHOT.jar
```

Swagger UI доступен по адресу `http://localhost:2222/swagger-ui.html`, OpenAPI JSON — `http://localhost:2222/v3/api-docs`.

Основные маршруты:


| Метод и путь                                                   | Операция                                          |
| -------------------------------------------------------------- | ------------------------------------------------- |
| `GET /api/v1/health`                                           | Состояние сервера                                 |
| `GET /api/v1/stats`                                            | Статистика БД                                     |
| `POST /api/v1/save`, `POST /api/v1/load`                       | Сохранение и загрузка                             |
| `POST /api/v1/collections`                                     | Создание коллекции с заданной размерностью        |
| `GET /api/v1/collections`                                      | Список коллекций                                  |
| `GET`, `DELETE /api/v1/collections/{name}`                     | Получение и удаление коллекции                    |
| `GET /api/v1/collections/{name}/stats`                         | Статистика коллекции, включая количество векторов |
| `PUT`, `GET`, `DELETE /api/v1/collections/{name}/vectors/{id}` | CRUD вектора                                      |
| `POST /api/v1/collections/{name}/vectors/search`               | k-NN поиск                                        |
| `GET /api/v1/replication/status`                               | Роль, LSN, лаг                                    |
| `GET /api/v1/replication/wal?n=20`                             | Хвост WAL (JSON)                                  |
| `POST /api/v1/replication/promote`                             | Ручной failover на ведомом                        |


Записи на ведущем возвращают LSN в теле (`lsn`) и заголовке `X-VecDB-LSN`. `GET .../vectors/{id}?waitLsn=N` на ведомом ждёт применения этой позиции (токен read-your-writes).

REST API остаётся stateless: имя коллекции передаётся в пути каждого запроса (`/collections/{name}/...`), состояния `USE` на сервере нет.

Настройки сервера задаются переменными окружения `SERVER_PORT`, `SERVER_ADDRESS`, `VECDB_NAME`, `VECDB_DATA_DIRECTORY`, `VECDB_AUTO_SAVE`, `VECDB_SAVE_INTERVAL`, `VECDB_ROLE`, `VECDB_REPLICATION_PORT`, `VECDB_MASTER_HOST`, `VECDB_MASTER_PORT` и `VECDB_REPLICATION_DELAY_MS`. Запуск пары узлов — в разделе «Реализация (этап 4)».

### Docker

Один узел (этап 3, роль `STANDALONE`):

```bash
docker compose up --build
```

Порт `2222` публикуется на хосте, данные — в томе `vecdb-data`. Остановка: `docker compose down` (том сохраняется).

Кластер репликации и демонстрации этапа 4: `compose.demo.yaml`, скрипты в `scripts/` (раздел «Демонстрации»).

## Теоретические основы

Векторная база данных хранит эмбеддинги, числовые представления объектов (текст, изображения и т.д.), и позволяет искать ближайшие по смыслу записи.


| Категория         | Операции в проекте                                                             | Где задано                                                        |
| ----------------- | ------------------------------------------------------------------------------ | ----------------------------------------------------------------- |
| **Базовые**       | CRUD по id, размер, очистка, закрытие, статистика                              | `IRamDB`, `IDatabase`                                             |
| **Коллекции**     | Создание/удаление/список коллекций, схема (размерность + метрика) на коллекцию | `ICollectionManager`, `ICollection`, `CollectionConfig`           |
| **Отличительные** | k-NN поиск, метрики расстояния, фиксированная размерность                      | `INearestSearch`, `IIndex`, `DistanceMetric`, `DistanceFunctions` |
| **Расширения**    | Персистентность, REST API, репликация, шарды                                   | `IPersistable`, REST-контроллеры, конфиг                          |




## Архитектура

Скелет построен по принципу разделения ответственности: хранилище, индекс, персистентность и транспорт.
Над хранилищем есть уровень **коллекций**: один инстанс БД содержит несколько именованных коллекций, у каждой своя размерность и метрика (как в Qdrant / Milvus / Chroma).

```
                    ┌─────────────────────────────────────┐
                    │ REST / консоль / VecDbClient        │  ← этап 3–4
                    │ CRUD, STATUS, WAL DUMP, waitLsn     │
                    └──────────────────┬──────────────────┘
                                       │
                    ┌──────────────────▼──────────────────┐
                    │  ReplicatedVectorDB (MASTER/REPLICA)│  ← этап 4
                    │  WAL (LSN) + TCP 1 master + 1 replica│
                    │  wraps IVectorDB                    │
                    └──────────────────┬──────────────────┘
                                       │
                    ┌──────────────────▼──────────────────┐
                    │              IVectorDB              │  ← фасад инстанса
                    │ IDatabase + ICollectionManager      │
                    │            + IPersistable           │
                    │  DbConfig: dataDirectory, role, …   │
                    └──────────────────┬──────────────────┘
                                       │ createCollection / getCollection
                    ┌──────────────────▼──────────────────┐
                    │        ICollection  (N штук)        │
                    │      IRamDB + INearestSearch        │
                    │ CollectionConfig: name, dimension,  │
                    │            metric, maxVectors       │
                    └──────┬───────────────┬──────────────┘
                           │               │
              ┌────────────▼────┐   ┌──────▼──────────┐
              │  Map<id, Entry> │   │     IIndex      │  ← k-NN
              │   (хранилище)   │   │  getNearest(k)  │
              └─────────────────┘   └─────────────────┘
```



### Фасад — `IVectorDB`

```java
public interface IVectorDB
        extends IDatabase, ICollectionManager, IPersistable { }
```

Единая точка входа: жизненный цикл, управление коллекциями, сохранение на диск. Работа с векторами — через `ICollection`:

```java
try (IVectorDB db = VectorDBFactory.open(DbConfig.builder().dataDirectory("./data").build())) {
    ICollection docs = db.createCollection("docs", 384, DistanceMetric.COSINE);
    docs.put(new VectorEntry("doc-1", embedding, payload));
    List<SearchResult> top5 = docs.getNearest(query, 5);
}
```



### Коллекции — `ICollectionManager`, `ICollection`


| Метод                                                                              | Поведение                                                              |
| ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| `createCollection(CollectionConfig)` / `createCollection(name, dimension, metric)` | Создаёт коллекцию; `CollectionAlreadyExistsException`, если имя занято |
| `getCollection(name)`                                                              | Возвращает коллекцию; `CollectionNotFoundException`, если её нет       |
| `findCollection(name)` / `hasCollection(name)`                                     | Проверка без исключения                                                |
| `dropCollection(name)`                                                             | Удаляет коллекцию вместе с данными, `false` — если её не было          |
| `listCollections()`                                                                | Имена всех коллекций                                                   |


Решения:

- **Размерность и метрика — часть схемы коллекции и неизменяемы.** Метрику диктует модель эмбеддингов, а индекс (HNSW/IVF) строится под конкретную метрику — поменять её «на лету» нельзя. Поэтому обе задаются при создании.
- **Размерность обязательна** (`CollectionConfig.builder(name, dimension)`), значения по умолчанию у неё нет; метрика по умолчанию — `COSINE`.
- **Проверка размерности** — в `ICollection.put` и `ICollection.getNearest` → `DimensionMismatchException`.
- **Имя коллекции** — `[A-Za-z0-9_-]{1,64}`, т.к. становится именем каталога; иначе `InvalidCollectionNameException`.



### Разбиение интерфейсов


| Интерфейс            | Ответственность                                                          | Зачем отдельно                                                                                                          |
| -------------------- | ------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| `IDatabase`          | Имя, `close()`, `stats()`, `isClosed()`                                  | Жизненный цикл и наблюдаемость                                                                                          |
| `IReplicationNode`   | `STATUS`, WAL dump, `PROMOTE`, ожидание LSN                              | Наблюдаемость и ручной failover; реализует `ReplicatedVectorDB`                                                         |
| `ICollectionManager` | `createCollection`, `getCollection`, `dropCollection`, `listCollections` | Схема данных отделена от операций над данными                                                                           |
| `ICollection`        | `config()`, `stats()` + `IRamDB` + `INearestSearch`                      | Одна коллекция = одна размерность, одна метрика, свой индекс                                                            |
| `IRamDB`             | `put`, `getById`, `delete`, `exists`, `size`, `clear`                    | Классический key-value слой поверх векторов                                                                             |
| `INearestSearch`     | `getNearest(query, k)`                                                   | Главная векторная операция, отделена от CRUD                                                                            |
| `IPersistable`       | `save`, `load`, `flush`                                                  | Отделяет работу с данными в RAM от записи/чтения с носителя. Персистентность можно подменить (файл, WAL) без смены API. |
| `IIndex`             | Индекс для k-NN: `add`, `remove`, `getNearest`                           | Сейчас `BruteForceIndex`; можно заменить на HNSW/LSH без смены API; один экземпляр на коллекцию                         |
| `ICommandDispatcher` | `dispatch(List<String>)`                                                 | Текстовый протокол локальной консоли: `PUT docs id 1 2 3`, `SEARCH docs 1 2 3 5`                                        |


Для `IPersistable`:
save() - записать текущее состояние БД (все коллекции) на диск
load() - восстановить коллекции из файлов в dataDirectory
flush() - синхронный сброс (save() без checked exception)

### Раскладка на диске

```
data/
├── wal.log               # этап 4: JSON-строки логических операций с LSN (append-only, fsync)
├── wal.checkpoint        # LSN последнего согласованного снимка
├── docs/
│   ├── meta.properties   # formatVersion=1, name, dimension, metric, maxVectors
│   └── vectors.bin       # записи коллекции + CRC32
└── images/
    ├── meta.properties
    └── vectors.bin
```

`load()` сканирует `dataDirectory`, читает `meta.properties` каждой подпапки и поднимает коллекцию с её схемой. Каталоги без `meta.properties` игнорируются. `formatVersion` заложен с первого дня: файл с другой версией не загружается молча, а даёт `StorageException`.

Формат `vectors.bin` (big-endian, `DataOutputStream`):

```
int    MAGIC "VDB1"
int    formatVersion
int    dimension
int    count
count × { int idLen, byte[] id (UTF-8), double[dimension] vector, int dataLen (-1 = null), byte[] data }
long   CRC32 всего, что выше
```

Надёжность записи: каждый файл пишется во временный `*.tmp`, сбрасывается на диск (`fsync`) и атомарно переименовывается. Если процесс упадёт посреди сохранения, предыдущая версия останется целой. Повреждённый или обрезанный файл распознаётся по CRC32 / EOF при загрузке.

## Реализация (этап 2)

```
com.db
├── Main                         локальная консоль (REPL)
├── index/BruteForceIndex        IIndex: точный k-NN перебором, top-k через кучу — O(n·d + n·log k)
├── storage/
│   ├── VectorDBFactory          STANDALONE → InMemoryVectorDB, иначе ReplicatedVectorDB
│   ├── InMemoryVectorDB         IVectorDB: коллекции, save/load, автосохранение, close
│   ├── InMemoryCollection       ICollection: хранилище + индекс, блокировки, статистика, вытеснение
│   └── CollectionFileStore      файловый формат (meta.properties + vectors.bin)
└── command/
    ├── TextCommandDispatcher    ICommandDispatcher: текстовые команды → вызовы IVectorDB
    └── ResponseFormatter        результат → человекочитаемый текст
```


| Решение               | Как сделано                                                                                                                                                                                                                           | Почему                                                                                           |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| Хранилище и индекс    | `LinkedHashMap<id, entry>` + `BruteForceIndex`, обновляются вместе под одной блокировкой                                                                                                                                              | Поиск никогда не видит запись, которой нет в хранилище, и наоборот                               |
| Конкурентность        | `ReentrantReadWriteLock` на коллекцию: get/exists/search параллельно, put/delete/clear эксклюзивно. На уровне БД — `schemaLock` (create/drop/load/close vs save) и `saveLock` (ручной save vs автосохранение)                         | Операции в разных коллекциях не блокируют друг друга                                             |
| Неизменяемость данных | Записи копируются при `put` и при выдаче (`get`, `search`)                                                                                                                                                                            | `VectorEntry` — record с массивами; без копий клиент мог бы изменить вектор в БД в обход индекса |
| Валидация             | Размерность, конечные значения (`NaN`/`Infinity` отклоняются), непустой id, `k ≥ 1`                                                                                                                                                   | Ошибка возникает при вставке, а не при поиске                                                    |
| `maxVectors`          | При переполнении вытесняется самая старая запись (FIFO), перезапись id делает запись «новой»; счётчик `evictedVectors`                                                                                                                | Предсказуемое поведение без настроек                                                             |
| Сохранение            | Пишутся только изменённые коллекции (по номеру версии коллекции)                                                                                                                                                                      | Автосохранение не переписывает неизменённые данные                                               |
| Автосохранение        | Фоновый daemon-поток раз в `saveInterval`; `close()` останавливает его и делает финальный `save()`                                                                                                                                    | Ограничивает потерю данных при сбое интервалом сохранения                                        |
| Жизненный цикл        | `InMemoryVectorDB.open(config)` создаёт каталог и загружает данные; после `close()` любые вызовы (в т.ч. через старые ссылки на коллекции) → `DatabaseClosedException`; ссылка на удалённую коллекцию → `CollectionNotFoundException` | Явные ошибки вместо тихой работы с «мертвыми» объектами                                          |




## Модель данных



### `VectorEntry`

```java
record VectorEntry(String id, double[] vector, byte[] data)
```

- **id** — уникальный ключ записи (как primary key).
- **vector** — эмбеддинг фиксированной размерности (задаётся в `CollectionConfig.dimension` коллекции, в которую кладётся запись).
- **data** — произвольная полезная нагрузка (метаданные, текст, JSON). Типичный паттерн vector DB: вектор для поиска, payload для отображения результата.

Валидация id и непустого вектора выполняется в конструкторе record. Проверка размерности — в коллекции, т.к. только она знает свою схему.

### `SearchResult`

```java
record SearchResult(VectorEntry entry, double distance)
```

Результат k-NN: найденная запись и расстояние до запроса. Меньшее значение - ближе (для всех метрик).

### `CollectionStats` и `DbStats`

`CollectionStats` — метрики одной коллекции: имя, размерность, метрика, число векторов, счётчики операций, hit/miss ratio, `evictedVectors` (вытеснение при достижении `maxVectors`, возможно на этапе 2).

`DbStats` — агрегат по инстансу: число коллекций, суммарные счётчики, время последнего сохранения и `perCollection` — статистика каждой коллекции.

## Метрики расстояния

`DistanceMetric` — три стандартных метрики для эмбеддингов:


| Метрика       | Смысл                                                  | Когда использовать                   |
| ------------- | ------------------------------------------------------ | ------------------------------------ |
| `COSINE`      | Угол между векторами (1 − cos θ)                       | Нормализованные эмбеддинги NLP       |
| `EUCLIDEAN`   | L2-расстояние в пространстве                           | Классическая геометрия               |
| `DOT_PRODUCT` | −(a·b), инвертирован для единообразия «меньше = ближе» | Максимизация скалярного произведения |


Реализация в `DistanceFunctions.compute(a, b, metric)` — обёртка над использованием конкретной метрики.

## Конфигурация



### `DbConfig` — инстанс


| Параметр                    | По умолчанию       | Назначение                                                                               |
| --------------------------- | ------------------ | ---------------------------------------------------------------------------------------- |
| `name`                      | `vecdb`            | Имя инстанса (`IDatabase.name()`)                                                        |
| `dataDirectory`             | `./data`           | Каталог для save/load; коллекция лежит в `dataDirectory/<name>` (`collectionPath(name)`) |
| `autoSave`                  | `true`             | Фоновое сохранение изменённых коллекций                                                  |
| `saveInterval`              | 5 сек              | Период автосохранения                                                                    |
| `role`                      | `STANDALONE`       | `STANDALONE` / `MASTER` / `REPLICA`                                                      |
| `replicationPort`           | 4700               | TCP-порт ведущего; `0` — эфемерный (тесты)                                               |
| `masterHost` / `masterPort` | `127.0.0.1` / 4700 | Куда подключается ведомый                                                                |
| `replicationDelayMs`        | 0                  | `Thread.sleep` перед apply на ведомом (`--replication-delay-ms`)                         |




### `CollectionConfig` — схема коллекции (record, неизменяемый)


| Параметр     | По умолчанию    | Назначение                           |
| ------------ | --------------- | ------------------------------------ |
| `name`       | — (обязательно) | Имя коллекции, `[A-Za-z0-9_-]{1,64}` |
| `dimension`  | — (обязательно) | Размерность векторов                 |
| `metric`     | `COSINE`        | Метрика для k-NN                     |
| `maxVectors` | 0 (без лимита)  | Верхняя граница записей в коллекции  |


```java
CollectionConfig docs = CollectionConfig.builder("docs", 384)
        .metric(DistanceMetric.COSINE)
        .maxVectors(100_000)
        .build();
// или коротко:
CollectionConfig images = CollectionConfig.of("images", 512, DistanceMetric.EUCLIDEAN);
```



## Исключения

Иерархия `DbException`:

- `StorageException` — ошибки save/load/flush
- `DimensionMismatchException` — вектор не совпадает по размерности со схемой коллекции
- `IdNotFoundException`, `InvalidIdException` — ошибки идентификатора
- `CollectionNotFoundException`, `CollectionAlreadyExistsException`, `InvalidCollectionNameException` — ошибки уровня коллекций
- `DatabaseClosedException` — обращение к закрытой БД
- `ReplicaReadOnlyException` — запись на ведомом (HTTP 403); пишет только ведущий, либо `PROMOTE`



## Тестирование

`bash mvnw test` (или `mvn test`):


| Тест                        | Что проверяет                                                                                                                                                                                                                                                                   |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `VectorStoreContractTest`   | Абстрактный контракт любой `ICollection`: CRUD, перезапись, k-NN (порядок, `k`, пустая коллекция, удалённые записи), валидация (размерность, NaN, пустой id, `k < 1`), статистика                                                                                               |
| `InMemoryCollectionTest`    | Наследует контракт + защитные копии массивов, FIFO-вытеснение, версия изменений, многопоточные put/search/delete                                                                                                                                                                |
| `InMemoryVectorDBTest`      | Коллекции (create/get/list/drop, изоляция, «мертвые» ссылки), сохранение и повторное открытие, сохранение при `close()`, удаление файлов при drop, `load()`, пропуск неизменённых коллекций, повреждённый и обрезанный файл, чужая `formatVersion`, автосохранение, закрытая БД |
| `BruteForceIndexTest`       | Порядок и `k`, равные расстояния, сверка с полной сортировкой на случайных данных для всех метрик                                                                                                                                                                               |
| `TextCommandDispatcherTest` | Сценарий команд от создания до удаления, ошибки синтаксиса, форматирование ответов, `STATUS` без репликации                                                                                                                                                                     |
| `VectorDbRestApiTest`       | REST CRUD и k-NN поиск, HTTP-коды ошибок, валидация и генерация OpenAPI, `GET /replication/status`                                                                                                                                                                              |
| `DistanceFunctionsTest`     | Значения метрик и разная длина векторов                                                                                                                                                                                                                                         |
| `WalLogTest`                | LSN, fsync-журнал, dump хвоста, JSON round-trip                                                                                                                                                                                                                                 |
| `ReplicatedVectorDBTest`    | Восстановление из WAL, checkpoint, лаг/RYW, догон после reconnect, `PROMOTE`                                                                                                                                                                                                    |


Новая реализация коллекции получает весь контракт, если унаследовать `VectorStoreContractTest` и реализовать `createStore(CollectionConfig)`.

## Реализация (этап 4) — репликация

Асинхронная репликация **1 ведущий + 1 ведомый**, журнал **логических** операций (append-only, LSN), JDK-сокеты и файлы без внешних библиотек. Чтения в штатном режиме — на ведущем; ведомый — резервная копия и площадка, чтобы показать лаг.

```
клиент ──writes──► MASTER ──append+fsync──► wal.log
                      │                      │
                      │ apply локально       │ TCP, JSON-строки с LSN
                      ▼                      ▼
                 InMemoryVectorDB      REPLICA: очередь → sleep(delay) → apply
                 (чтение, k-NN)        lastAppliedLsn; lag = masterLsn − lastAppliedLsn
```

`VectorDBFactory.open` оставляет `STANDALONE` как раньше (`InMemoryVectorDB`). Роли `MASTER`/`REPLICA` оборачивают тот же инстанс в `ReplicatedVectorDB`: консоль, REST и тесты по-прежнему работают через `IVectorDB`.


| Решение   | Как сделано                                                                                           | Почему так просто                                                           |
| --------- | ----------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| Режим     | Асинхронно: ведущий отвечает клиенту после своего WAL+apply, ведомому досылает журнал в фоне          | Учебный лаг без блокировки записи                                           |
| Топология | Ведущий слушает TCP (`replicationPort`), ведомый сам подключается и шлёт `HELLO {lastAppliedLsn}`     | Один ведомый, догон после обрыва — хвост WAL                                |
| WAL       | `wal.log`: одна JSON-строка на `CREATE/DROP/PUT/DELETE/CLEAR`, LSN++, `FileChannel.force`             | Логические операции = тот же контракт, что и консоль                        |
| Снимок    | Как в этапе 2; `wal.checkpoint` = LSN на момент `save()`. Старт: snapshot + replay `lsn > checkpoint` | WAL для durability и догона, файлы коллекций — чтобы не крутить весь журнал |
| Запись    | Только `MASTER`. Ведомый кидает `ReplicaReadOnlyException`                                            | Один источник истины                                                        |
| Чтение    | Клиент читает ведущего. Ведомый отдаёт GET/SEARCH для демо лага                                       | RYW на реплике не обещан                                                    |
| Failover  | Команда / `POST /api/v1/replication/promote`                                                          | Ручной, без fencing и split-brain защиты                                    |
| Задержка  | `--replication-delay-ms` / `VECDB_REPLICATION_DELAY_MS`: `Thread.sleep` в цикле apply                 | Стабильно воспроизвести лаг                                                 |




### Наблюдаемость

1. `STATUS` (оба узла) — `role`, у ведущего `lastLsn`, у ведомого `lastAppliedLsn`, `masterLsn` из последней полученной записи, `lag = masterLsn - lastAppliedLsn`.
2. `WAL DUMP [N]` — последние N записей журнала в JSON.
3. `--replication-delay-ms` — задержка apply на ведомом.
4. **Лог с LSN:** `[MASTER] applied PUT id=7 lsn=42`, `[REPLICA] applied PUT id=7 lsn=42`, `[MASTER] sent lsn=42`, `[CLIENT] write ok lsn=42`.
5. `VecDbClient` **+** `LsnToken`**:** запись на ведущем возвращает токен; `get(..., token)` ходит с `?waitLsn=` и ждёт, пока ведомый догонит.

Пример `STATUS`:

```
role=MASTER lastLsn=42 lag=0 replicationPort=4700
role=REPLICA lastAppliedLsn=40 masterLsn=42 lag=2 delayMs=500
```

REST: `GET /api/v1/replication/status`, `GET /api/v1/replication/wal?n=20`, заголовок `X-VecDB-LSN` и поле `lsn` в ответе на запись.

### Синхронная репликация как расширение

Можно не отпускать клиента, пока ведомый не подтвердит LSN. Цена: каждая запись ждёт сеть и apply (хвост латентности = RTT + apply); нужны таймауты и политика при молчании ведомого (отказать / деградировать в async). Ведомый становится критической точкой отказа: без него ведущий либо встаёт, либо врёт про «синхронность». Для учебного стенда оставляем async.

### Кластер REST (без Docker)

```bash
java -jar target/lab-db-0.0.1-SNAPSHOT.jar --server.port=2222 --vecdb.role=MASTER \
  --vecdb.replication-port=4700 --vecdb.data-directory=./data-master --vecdb.auto-save=false

java -jar target/lab-db-0.0.1-SNAPSHOT.jar --server.port=2223 --vecdb.role=REPLICA \
  --vecdb.master-host=127.0.0.1 --vecdb.master-port=4700 --vecdb.replication-delay-ms=500 \
  --vecdb.data-directory=./data-replica --vecdb.auto-save=false
```

Консоль без Spring: `com.db.Main --role master|replica ...` (флаги те же).

### Демонстрации

Каждый скрипт поднимает кластер из `compose.demo.yaml` (ведущий `:2222`, ведомый `:2223`, delay 2 с, без автосохранения), прогоняет сценарий и оставляет контейнеры. Команды сценария — только в скриптах.


| Сценарий | Что демонстрирует | Windows | macOS / Linux |
|---|---|---|---|
| read-your-writes (аномалия, затем LSN-токен) | После записи на мастер чтение с реплики сразу возвращает 404 из-за отставания репликации. Затем показывается решение: чтение с реплики с `waitLsn`, чтобы дождаться применения записи до нужного LSN. | `.\scripts\demo-read-your-writes.ps1` | `bash scripts/demo-read-your-writes.sh` |
| ручной failover (`PROMOTE`) | Запись на реплику отклоняется с 403. Мастер убивается, реплика повышается через `PROMOTE`. Дальше запись и чтение идут на новом мастере. Показано предупреждение: старый мастер запускать нельзя, два мастера не поддерживаются. | `.\scripts\demo-failover.ps1` | `bash scripts/demo-failover.sh` |
| восстановление мастера из WAL | Мастер пишет данные, затем получает `SIGKILL` без снапшота. После запуска того же контейнера с тем же volume состояние восстанавливается из WAL, и данные снова доступны для чтения. | `.\scripts\demo-wal-recovery.ps1` | `bash scripts/demo-wal-recovery.sh` |
| догон ведомого после reconnect | Реплика временно останавливается, мастер продолжает писать. После повторного запуска реплика догоняет мастера через WAL, и записи, сделанные во время её отсутствия, появляются на реплике. | `.\scripts\demo-replica-catchup.ps1` | `bash scripts/demo-replica-catchup.sh` |


Нужны Docker и `curl` (`curl.exe` в PowerShell). Логи: `docker compose -f compose.demo.yaml logs -f`. Остановка: `docker compose -f compose.demo.yaml down -v`.

## План развития по этапам



### Этап 2 — реализация ✅

См. раздел «Реализация (этап 2)» выше.

### Этап 3 — REST ✅

```
Spring Boot / Tomcat (2222)
├── DatabaseController
├── CollectionController
├── VectorController
├── ReplicationController
└── IVectorDB → VectorDBFactory → ICollection
```

Контроллеры принимают JSON, выполняют валидацию и вызывают интерфейсы БД, реализованные во второй лабораторной. Документация контракта генерируется в OpenAPI, интерактивный клиент доступен через Swagger UI. Для развёртывания подготовлены `Dockerfile` и `compose.yaml`. Репликация подключается той же фабрикой (`vecdb.role`), не отдельным сервером.

### Этап 4 — репликация ✅

См. раздел «Реализация (этап 4)» выше.

### Этап 5 — шардирование

- Шард - отдельный экземпляр `IVectorDB` со своим `dataDirectory`; коллекция с одним именем существует на всех шардах с одинаковой схемой
- Маршрутизация по hash(id) или по диапазону id или придумаем более умный кейс.
- `ICommandDispatcher` на верхнем уровне направляет команду нужному шарду

