# Discovery Device Repository

이 디렉터리는 `DiscoveryDeviceRepositoryOperations` 구현을 프로젝트에 머지하기 위한 Kotlin/AIDL 코드 묶음입니다.

패키지명 `your.package.discovery`는 실제 프로젝트 패키지로 교체하세요. `DiscoveryDevice`, `QcDevice`, `AbstractDiscoveryManager`, `DeviceDiscoveryResultListener`는 기존 프로젝트 타입을 사용한다는 전제로 작성했습니다.

## 최종 구조

```text
DiscoveryDeviceRepositoryOperations
├─ DiscoveryDeviceRepositoryImpl
│  └─ DiscoveryManagerDiscoveryDeviceResource
│     └─ AbstractDiscoveryManager
│
└─ DiscoveryDeviceRepositoryProxy
   └─ AidlDiscoveryDeviceResource
      └─ IDiscoveryDeviceRepository
         └─ DiscoveryDeviceRepositoryAidlBinder
            └─ DiscoveryManagerDiscoveryDeviceResource
```

## 전체 시퀀스

```text
MAIN_UI process
1. Caller가 DiscoveryDeviceRepositoryProxy.discoveryDeviceListFlow를 collect한다.
2. SharedFlow가 WhileSubscribed 정책으로 callbackFlow upstream을 시작한다.
3. AidlDiscoveryDeviceResource.registerListener()가 Core process의 IDiscoveryDeviceRepository.registerCallback()을 호출한다.
4. AidlDiscoveryDeviceResource.getInitialDevices()는 Core에서 onInitialDevices()가 올 때까지 기다린다.

CORE process
5. DiscoveryDeviceRepositoryAidlBinder.registerCallback()이 Proxy callback을 RemoteCallbackList에 등록한다.
6. 첫 callback이면 DiscoveryManagerDiscoveryDeviceResource.deviceListFlow collect를 시작한다.
7. DiscoveryManagerDiscoveryDeviceResource.registerListener()가 AbstractDiscoveryManager.registerDiscoveryResultListener()를 호출한다.
8. DiscoveryManagerDiscoveryDeviceResource.getInitialDevices()가 AbstractDiscoveryManager.getDevices()를 읽는다.
9. DiscoveryDeviceResource가 최초 리스트를 emit한다.
10. Binder가 최초 리스트를 onInitialDevices()로 Proxy에 전달한다.

변경 발생
11. AbstractDiscoveryManager.notifyDiscoveryResult()가 DeviceDiscoveryResultListener.onDiscoveryResult()를 호출한다.
12. DiscoveryManagerDiscoveryDeviceResource가 QcDevice를 DiscoveryDevice로 변환하고 DiscoveryDeviceChange를 만든다.
13. DiscoveryDeviceResource.DeviceStore가 변경분을 내부 리스트에 반영한 뒤 최신 전체 리스트를 emit한다.
14. Binder가 최신 리스트와 이전 리스트를 비교해 ADDED/UPDATED/REMOVED 변경분만 AIDL onChanged()로 전달한다.
15. Proxy의 AidlDiscoveryDeviceResource가 변경분을 자기 DeviceStore에 반영한다.
16. Proxy.discoveryDeviceListFlow collector는 최신 전체 리스트를 받는다.

구독 종료
17. MAIN_UI에서 마지막 collector가 사라지면 AidlDiscoveryDeviceResource의 awaitClose가 호출된다.
18. AidlDiscoveryDeviceResource.unregisterListener()가 Core callback을 해제한다.
19. Core Binder의 callback 수가 0이 되면 collectJob을 cancel한다.
20. Core DiscoveryDeviceResource도 awaitClose가 호출되어 AbstractDiscoveryManager.unregisterDiscoveryResultListener()를 호출하고 캐시를 비운다.
```

## 클래스별 역할

### DiscoveryDeviceRepositoryOperations

공통 Repository 인터페이스입니다. 요구사항대로 `discoveryDeviceListFlow` 하나만 공개합니다.

### DiscoveryDeviceRepositoryImpl

Core process에서 사용하는 구현체입니다. 실제 로직은 `DiscoveryManagerDiscoveryDeviceResource`에 위임합니다.

### DiscoveryDeviceRepositoryProxy

MAIN_UI process에서 사용하는 구현체입니다. 실제 로직은 `AidlDiscoveryDeviceResource`에 위임합니다.

### DiscoveryDeviceResource

공통 캐시와 Flow lifecycle을 담당합니다.

- `callbackFlow`로 listener 등록/해제를 Flow lifecycle에 묶습니다.
- `shareIn(SharingStarted.WhileSubscribed(...), replay = 1)`로 구독자가 있을 때만 동작합니다.
- 최초 리스트가 적용되기 전에 들어온 변경 이벤트는 `pendingChanges`에 보관했다가 최초 리스트 적용 직후 반영합니다.
- 구독자가 0명이 되면 `awaitClose`에서 listener를 해제하고 내부 캐시를 비웁니다.

### DeviceStore

`DiscoveryDeviceResource` 내부 클래스입니다.

- 최초 리스트 적용
- ADDED/UPDATED/REMOVED 변경 반영
- 최초 리스트 적용 전 pending 변경 보관
- 최신 전체 리스트 복사본 생성

`DiscoveryDevice.equals()`가 같은 기기 식별 기준으로 동작한다는 전제에서 `MutableList.indexOf()`로 기존 기기를 찾아 업데이트합니다.

### DiscoveryManagerDiscoveryDeviceResource

Core process에서 `AbstractDiscoveryManager`와 직접 연결되는 Resource입니다.

- `registerListener()`에서 `registerDiscoveryResultListener()` 호출
- `getInitialDevices()`에서 `getDevices()` 호출
- `unregisterListener()`에서 `unregisterDiscoveryResultListener()` 호출
- discovery event int를 `DiscoveryDeviceChange.Type`으로 변환
- `QcDevice`를 `DiscoveryDevice`로 변환

### AidlDiscoveryDeviceResource

MAIN_UI process에서 Core process의 Binder와 연결되는 Resource입니다.

- `registerListener()`에서 `IDiscoveryDeviceRepository.registerCallback()` 호출
- `getInitialDevices()`에서 `onInitialDevices()` callback을 기다림
- `onChanged()` callback을 `DiscoveryDeviceChange`로 변환해 공통 Resource 캐시에 반영
- `unregisterListener()`에서 AIDL callback 해제

### DiscoveryDeviceRepositoryAidlBinder

Core process의 기존 바인딩 로직에서 반환할 Binder입니다.

- Proxy callback을 `RemoteCallbackList`로 관리합니다.
- callback이 1개 이상 있을 때만 Core Resource를 collect합니다.
- 최초 전체 리스트는 `onInitialDevices()`로 전달합니다.
- 이후에는 이전 리스트와 현재 리스트를 비교해 변경분만 `onChanged()`로 전달합니다.

## 업데이트 diff 주의점

`DiscoveryDeviceResource.DeviceStore`는 `DiscoveryDevice.equals()`가 같은 기기 식별 기준이라고 가정합니다.

Binder가 `UPDATED`를 정확히 구분하려면 두 가지 비교가 필요합니다.

- `isSameDevice`: 같은 기기인지 판단
- `hasSameContent`: AIDL로 업데이트를 보낼 만큼 내용이 같은지 판단

기본값은 둘 다 `==`입니다. 만약 `equals()`가 기기 id 기준이라면, 업데이트를 감지하려면 Binder 생성 시 `hasSameContent`를 별도로 넘겨야 합니다.

예시:

```kotlin
DiscoveryDeviceRepositoryAidlBinder(
    scope = scope,
    resource = resource,
    isSameDevice = { old, new -> old == new },
    hasSameContent = { old, new ->
        old.name == new.name &&
            old.status == new.status &&
            old.rssi == new.rssi
    },
)
```

## 프로젝트에 머지할 때 바꿀 부분

- `your.package.discovery` 패키지명
- 실제 `DiscoveryDevice` Parcelable 위치
- 실제 `QcDevice` import
- 실제 `AbstractDiscoveryManager` import
- 실제 `DeviceDiscoveryResultListener` import
- `qcDeviceMapper`
- `discoveryEventMapper`
- `remoteRepositoryProvider`
- Core process의 기존 Service/Binder 연결 지점

## AIDL 파일

`aidl/` 하위 파일들은 실제 Android 모듈의 AIDL source set으로 옮기면 됩니다.

```text
aidl/IDiscoveryDeviceRepository.aidl
aidl/IDiscoveryDeviceRepositoryCallback.aidl
aidl/DiscoveryDevice.aidl
aidl/DiscoveryDeviceAidlChange.aidl
```

`DiscoveryDeviceAidlChange` Kotlin Parcelable은 AIDL parcelable 선언과 같은 패키지에 있어야 합니다.
