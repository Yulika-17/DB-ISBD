# vecDB

Учебная векторная база данных на Java.

## Статус

| Этап | Описание | Статус |
|------|----------|--------|
| 1 | Интерфейсы и скелет | ✅ |
| 2 | In-memory реализация, персистентность, локальная консоль | ✅ |
| 3 | REST-сервер | ⬜ |
| 4 | Репликация | ⬜ |
| 5 | Шардирование | ⬜ |

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

| Команда | Описание |
|---------|----------|
| `CREATE_COLLECTION <name> <dim> [COSINE\|EUCLIDEAN\|DOT_PRODUCT] [maxVectors]` | Создать коллекцию |
| `DROP_COLLECTION <name>` | Удалить коллекцию вместе с файлами |
| `LIST_COLLECTIONS`, `DESCRIBE <name>` | Список коллекций / схема коллекции |
| `PUT <coll> <id> <v1> … <vN> [payload…]` | Вставить/перезаписать; N = размерность коллекции, остаток строки — payload (UTF-8) |
| `GET <coll> <id>`, `EXISTS <coll> <id>`, `DELETE <coll> <id>` | Операции по id |
| `SIZE <coll>`, `CLEAR <coll>` | Размер / очистка коллекции |
| `SEARCH <coll> <v1> … <vN> <k>` | k ближайших соседей |
| `STATS [coll]` | Статистика БД или коллекции |
| `SAVE`, `LOAD` | Сохранить изменения / перечитать с диска (несохранённое теряется) |
| `HELP`, `EXIT` | Справка / выход |

Регистр команд не важен, строки с `#` — комментарии.


## Теоретические основы

Векторная база данных хранит эмбеддинги, числовые представления объектов (текст, изображения и т.д.), и позволяет искать ближайшие по смыслу записи.

| Категория | Операции в проекте | Где задано |
|-----------|-------------------|------------|
| **Базовые** | CRUD по id, размер, очистка, закрытие, статистика | `IRamDB`, `IDatabase` |
| **Коллекции** | Создание/удаление/список коллекций, схема (размерность + метрика) на коллекцию | `ICollectionManager`, `ICollection`, `CollectionConfig` |
| **Отличительные** | k-NN поиск, метрики расстояния, фиксированная размерность | `INearestSearch`, `IIndex`, `DistanceMetric`, `DistanceFunctions` |
| **Расширения** | Персистентность, REST-команды, репликация, шарды | `IPersistable`, `ICommandDispatcher`, конфиг |


## Архитектура

Скелет построен по принципу разделения ответственности: хранилище, индекс, персистентность и транспорт.
Над хранилищем есть уровень **коллекций**: один инстанс БД содержит несколько именованных коллекций, у каждой своя размерность и метрика (как в Qdrant / Milvus / Chroma).

```
                    ┌─────────────────────────────────────┐
                    │           ICommandDispatcher        │  ← этап 3 (REST)
                    │     dispatch(List<String> command)  │
                    └──────────────────┬──────────────────┘
                                       │ вызывает
                    ┌──────────────────▼──────────────────┐
                    │              IVectorDB              │  ← фасад инстанса
                    │ IDatabase + ICollectionManager      │
                    │            + IPersistable           │
                    │  DbConfig: dataDirectory, interval  │
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
try (IVectorDB db = InMemoryVectorDB.open(DbConfig.builder().dataDirectory("./data").build())) {
    ICollection docs = db.createCollection("docs", 384, DistanceMetric.COSINE);
    docs.put(new VectorEntry("doc-1", embedding, payload));
    List<SearchResult> top5 = docs.getNearest(query, 5);
}
```

### Коллекции — `ICollectionManager`, `ICollection`

| Метод | Поведение |
|-------|-----------|
| `createCollection(CollectionConfig)` / `createCollection(name, dimension, metric)` | Создаёт коллекцию; `CollectionAlreadyExistsException`, если имя занято |
| `getCollection(name)` | Возвращает коллекцию; `CollectionNotFoundException`, если её нет |
| `findCollection(name)` / `hasCollection(name)` | Проверка без исключения |
| `dropCollection(name)` | Удаляет коллекцию вместе с данными, `false` — если её не было |
| `listCollections()` | Имена всех коллекций |

Решения:
- **Размерность и метрика — часть схемы коллекции и неизменяемы.** Метрику диктует модель эмбеддингов, а индекс (HNSW/IVF) строится под конкретную метрику — поменять её «на лету» нельзя. Поэтому обе задаются при создании.
- **Размерность обязательна** (`CollectionConfig.builder(name, dimension)`), значения по умолчанию у неё нет; метрика по умолчанию — `COSINE`.
- **Проверка размерности** — в `ICollection.put` и `ICollection.getNearest` → `DimensionMismatchException`.
- **Имя коллекции** — `[A-Za-z0-9_-]{1,64}`, т.к. становится именем каталога; иначе `InvalidCollectionNameException`.

### Разбиение интерфейсов

| Интерфейс | Ответственность | Зачем отдельно |
|-----------|-----------------|----------------|
| `IDatabase` | Имя, `close()`, `stats()`, `isClosed()` | Жизненный цикл и наблюдаемость; пригодится для репликации (этап 4) |
| `ICollectionManager` | `createCollection`, `getCollection`, `dropCollection`, `listCollections` | Схема данных отделена от операций над данными |
| `ICollection` | `config()`, `stats()` + `IRamDB` + `INearestSearch` | Одна коллекция = одна размерность, одна метрика, свой индекс |
| `IRamDB` | `put`, `getById`, `delete`, `exists`, `size`, `clear` | Классический key-value слой поверх векторов |
| `INearestSearch` | `getNearest(query, k)` | Главная векторная операция, отделена от CRUD |
| `IPersistable` | `save`, `load`, `flush` | Отделяет работу с данными в RAM от записи/чтения с носителя. Персистентность можно подменить (файл, WAL) без смены API. |
| `IIndex` | Индекс для k-NN: `add`, `remove`, `getNearest` | Сейчас `BruteForceIndex`; можно заменить на HNSW/LSH без смены API; один экземпляр на коллекцию |
| `ICommandDispatcher` | `dispatch(List<String>)` | Текстовый протокол для REST (этап 3): `PUT docs id 1 2 3`, `SEARCH docs 1 2 3 5` |

Для `IPersistable`:
save() - записать текущее состояние БД (все коллекции) на диск
load() - восстановить коллекции из файлов в dataDirectory
flush() - синхронный сброс (save() без checked exception)

### Раскладка на диске

```
data/
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
│   ├── InMemoryVectorDB         IVectorDB: коллекции, save/load, автосохранение, close
│   ├── InMemoryCollection       ICollection: хранилище + индекс, блокировки, статистика, вытеснение
│   └── CollectionFileStore      файловый формат (meta.properties + vectors.bin)
└── command/
    ├── TextCommandDispatcher    ICommandDispatcher: текстовые команды → вызовы IVectorDB
    └── ResponseFormatter        результат → человекочитаемый текст
```

| Решение | Как сделано | Почему |
|---------|-------------|--------|
| Хранилище и индекс | `LinkedHashMap<id, entry>` + `BruteForceIndex`, обновляются вместе под одной блокировкой | Поиск никогда не видит запись, которой нет в хранилище, и наоборот |
| Конкурентность | `ReentrantReadWriteLock` на коллекцию: get/exists/search параллельно, put/delete/clear эксклюзивно. На уровне БД — `schemaLock` (create/drop/load/close vs save) и `saveLock` (ручной save vs автосохранение) | Операции в разных коллекциях не блокируют друг друга |
| Неизменяемость данных | Записи копируются при `put` и при выдаче (`get`, `search`) | `VectorEntry` — record с массивами; без копий клиент мог бы изменить вектор в БД в обход индекса |
| Валидация | Размерность, конечные значения (`NaN`/`Infinity` отклоняются), непустой id, `k ≥ 1` | Ошибка возникает при вставке, а не при поиске |
| `maxVectors` | При переполнении вытесняется самая старая запись (FIFO), перезапись id делает запись «новой»; счётчик `evictedVectors` | Предсказуемое поведение без настроек |
| Сохранение | Пишутся только изменённые коллекции (по номеру версии коллекции) | Автосохранение не переписывает неизменённые данные |
| Автосохранение | Фоновый daemon-поток раз в `saveInterval`; `close()` останавливает его и делает финальный `save()` | Ограничивает потерю данных при сбое интервалом сохранения |
| Жизненный цикл | `InMemoryVectorDB.open(config)` создаёт каталог и загружает данные; после `close()` любые вызовы (в т.ч. через старые ссылки на коллекции) → `DatabaseClosedException`; ссылка на удалённую коллекцию → `CollectionNotFoundException` | Явные ошибки вместо тихой работы с «мертвыми» объектами |


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

| Метрика | Смысл | Когда использовать |
|---------|-------|-------------------|
| `COSINE` | Угол между векторами (1 − cos θ) | Нормализованные эмбеддинги NLP |
| `EUCLIDEAN` | L2-расстояние в пространстве | Классическая геометрия |
| `DOT_PRODUCT` | −(a·b), инвертирован для единообразия «меньше = ближе» | Максимизация скалярного произведения |

Реализация в `DistanceFunctions.compute(a, b, metric)` — обёртка над использованием конкретной метрики.


## Конфигурация

### `DbConfig` — инстанс

| Параметр | По умолчанию | Назначение |
|----------|--------------|------------|
| `name` | `vecdb` | Имя инстанса (`IDatabase.name()`) |
| `dataDirectory` | `./data` | Каталог для save/load; коллекция лежит в `dataDirectory/<name>` (`collectionPath(name)`) |
| `autoSave` | `true` | Фоновое сохранение изменённых коллекций |
| `saveInterval` | 5 сек | Период автосохранения |

### `CollectionConfig` — схема коллекции (record, неизменяемый)

| Параметр | По умолчанию | Назначение |
|----------|--------------|------------|
| `name` | — (обязательно) | Имя коллекции, `[A-Za-z0-9_-]{1,64}` |
| `dimension` | — (обязательно) | Размерность векторов |
| `metric` | `COSINE` | Метрика для k-NN |
| `maxVectors` | 0 (без лимита) | Верхняя граница записей в коллекции |

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


## Тестирование

`bash mvnw test` (или `mvn test`):

| Тест | Что проверяет |
|------|---------------|
| `VectorStoreContractTest` | Абстрактный контракт любой `ICollection`: CRUD, перезапись, k-NN (порядок, `k`, пустая коллекция, удалённые записи), валидация (размерность, NaN, пустой id, `k < 1`), статистика |
| `InMemoryCollectionTest` | Наследует контракт + защитные копии массивов, FIFO-вытеснение, версия изменений, многопоточные put/search/delete |
| `InMemoryVectorDBTest` | Коллекции (create/get/list/drop, изоляция, «мертвые» ссылки), сохранение и повторное открытие, сохранение при `close()`, удаление файлов при drop, `load()`, пропуск неизменённых коллекций, повреждённый и обрезанный файл, чужая `formatVersion`, автосохранение, закрытая БД |
| `BruteForceIndexTest` | Порядок и `k`, равные расстояния, сверка с полной сортировкой на случайных данных для всех метрик |
| `TextCommandDispatcherTest` | Сценарий команд от создания до удаления, ошибки синтаксиса, форматирование ответов |
| `DistanceFunctionsTest` | Значения метрик и разная длина векторов |

Новая реализация коллекции получает весь контракт, если унаследовать `VectorStoreContractTest` и реализовать `createStore(CollectionConfig)`.


## План развития по этапам

### Этап 2 — реализация ✅

См. раздел «Реализация (этап 2)» выше.

### Этап 3 — REST

```
HttpServer / Spring Boot
└── RestCommandDispatcher implements ICommandDispatcher
    └── парсинг команд, вызовы IVectorDB
```

Пример протокола. Имя коллекции передаётся явно в каждой команде (без состояния `USE`), т.к. REST stateless — аналог `/collections/{name}/points` в Qdrant:

```
CREATE_COLLECTION docs 3 COSINE
LIST_COLLECTIONS
DROP_COLLECTION docs

PUT    docs vec-1  0.1 0.2 0.3
GET    docs vec-1
DELETE docs vec-1
SEARCH docs 0.1 0.2 0.3  5
STATS  [docs]
SAVE
```

### Этап 4 — репликация

- Primary принимает записи, replica синхронизируется. Переключаемся на replica при недоступности primary.
- `IPersistable.save/load` + журнал операций WAL для синхронизации (пока не придумали, как проще)
- `IDatabase.stats()` для мониторинга lag

### Этап 5 — шардирование

- Шард - отдельный экземпляр `IVectorDB` со своим `dataDirectory`; коллекция с одним именем существует на всех шардах с одинаковой схемой
- Маршрутизация по hash(id) или по диапазону id или придумаем более умный кейс.
- `ICommandDispatcher` на верхнем уровне направляет команду нужному шарду

