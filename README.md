# vecDB

Учебная векторная база данных на Java.

## Статус

| Этап | Описание | Статус |
|------|----------|--------|
| 1 | Интерфейсы и скелет | ✅ |
| 2 | In-memory реализация | ⬜ |
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
                    │        ICollection  (N штук)        │  ← этап 2
                    │      IRamDB + INearestSearch        │
                    │ CollectionConfig: name, dimension,  │
                    │            metric, maxVectors       │
                    └──────┬───────────────┬──────────────┘
                           │               │
              ┌────────────▼────┐   ┌──────▼──────────┐
              │  Map<id, Entry> │   │     IIndex      │  ← k-NN (этап 2)
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
try (IVectorDB db = new InMemoryVectorDB(DbConfig.builder().dataDirectory("./data").build())) {
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
| `IIndex` | Индекс для k-NN: `add`, `remove`, `getNearest` | Brute-force или HNSW/LSH на этап 2; один экземпляр на коллекцию |
| `ICommandDispatcher` | `dispatch(List<String>)` | Текстовый протокол для REST (этап 3): `PUT docs id 1 2 3`, `SEARCH docs 1 2 3 5` |

Для `IPersistable`:
save() - записать текущее состояние БД (все коллекции) на диск
load() - восстановить коллекции из файлов в dataDirectory
flush() - синхронный сброс (save() без checked exception)

### Раскладка на диске

```
data/
├── docs/
│   ├── meta.json      # {"formatVersion": 1, "name": "docs", "dimension": 384, "metric": "COSINE", "maxVectors": 0}
│   └── vectors.bin    # записи коллекции
└── images/
    ├── meta.json
    └── vectors.bin
```

`load()` сканирует `dataDirectory`, читает `meta.json` каждой подпапки и поднимает коллекцию с её схемой. `formatVersion` заложен с первого дня, чтобы будущие изменения формата шли через явную миграцию.


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
| `dataDirectory` | `./data` | Каталог для save/load; коллекция лежит в `dataDirectory/<name>` (`collectionPath(name)`) |
| `saveInterval` | 5 сек | Период автосохранения (этап 2) |

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


## Тестирование

`VectorStoreContractTest` — абстрактный набор тестов для любой реализации `IVectorDB` / `ICollection`:

- CRUD: put, get, delete, overwrite, clear
- k-NN: ближайший сосед, ограничение k, пустая база
- валидация: пустой id, неверная размерность в `put` и `getNearest`
- коллекции: create / get / drop / list, повторное создание, несуществующая коллекция, недопустимое имя, изоляция данных между коллекциями

На этапе 2 достаточно создать класс вида `class InMemoryVectorDBTest extends VectorStoreContractTest` с методом `createStore()` — и весь контракт проверится автоматически.


## План развития по этапам

### Этап 2 — реализация

```
InMemoryVectorDB implements IVectorDB
├── ConcurrentHashMap<String, InMemoryCollection> collections
├── create/get/drop/list коллекций
└── save/load: dataDirectory/<name>/meta.json + vectors.bin

InMemoryCollection implements ICollection
├── HashMap<String, VectorEntry> storage
├── BruteForceIndex implements IIndex (метрика из CollectionConfig)
├── синхронизация storage и index при put/delete
├── проверка размерности и maxVectors из CollectionConfig
└── ReadWriteLock на коллекцию
```

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

