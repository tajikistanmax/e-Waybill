
# ПРИЛОЖЕНИЯ К ТЕХНИЧЕСКОМУ ЗАДАНИЮ
## на создание государственной платформы «Электронный путевой лист» (ЭПЛ)

---

## ПРИЛОЖЕНИЕ А. ПОЛНЫЕ XML-СХЕМЫ ЭПЛ (СОГЛАСНО ПРИКАЗУ ФНС № ЕД-7-26/116@)

### А.1. Общие положения

XML-схема электронного путевого листа разработана в соответствии с Приказом ФНС России от 17.02.2023 № ЕД-7-26/116@ и описывает структуру XML-файлов, передаваемых в ГИС ЭПД.

**Состав XML-пакета ЭПЛ:**

| № | Имя файла | Описание | Обязательность |
| :--- | :--- | :--- | :--- |
| 1 | `EPD_PL_Титул1.xml` | Сведения об обстоятельствах и особенностях рейса | Обязательный |
| 2 | `EPD_PL_Титул2.xml` | Сведения о предрейсовом медосмотре | Обязательный |
| 3 | `EPD_PL_Титул3.xml` | Сведения о техническом осмотре ТС | Обязательный |
| 4 | `EPD_PL_Титул4.xml` | Сведения о показаниях одометра при выезде | Обязательный |
| 5 | `EPD_PL_Титул5.xml` | Сведения о показаниях одометра при въезде | Обязательный |
| 6 | `EPD_PL_Титул6.xml` | Сведения о послерейсовом медосмотре | Условно-обязательный |

### А.2. Полная XML-схема Титула 1

```xml
<?xml version="1.0" encoding="UTF-8"?>
<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
           targetNamespace="http://www.nalog.ru/EPD/PL/Titul1"
           xmlns:tns="http://www.nalog.ru/EPD/PL/Titul1">

  <!-- Корневой элемент -->
  <xs:element name="Титул1">
    <xs:complexType>
      <xs:sequence>
        <!-- 1. Общие сведения о рейсе -->
        <xs:element name="ОбщиеСведения">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="ВидПеревозки" type="tns:ВидПеревозкиТип" minOccurs="1" maxOccurs="1"/>
              <xs:element name="ВидСообщения" type="tns:ВидСообщенияТип" minOccurs="1" maxOccurs="1"/>
              <xs:element name="ДатаНачалаРейса" type="xs:date" minOccurs="1" maxOccurs="1"/>
              <xs:element name="ВремяНачалаРейса" type="xs:time" minOccurs="1" maxOccurs="1"/>
              <xs:element name="СрокДействия" type="xs:date" minOccurs="1" maxOccurs="1"/>
              <xs:element name="ПризнакПослерейсовогоОсмотра" type="xs:boolean" minOccurs="1" maxOccurs="1"/>
            </xs:sequence>
          </xs:complexType>
        </xs:element>

        <!-- 2. Сведения о собственнике ТС -->
        <xs:element name="СобственникТС" minOccurs="1" maxOccurs="1">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="ИНН" type="tns:ИННТип" minOccurs="0" maxOccurs="1"/>
              <xs:element name="ОГРН" type="tns:ОГРНТип" minOccurs="0" maxOccurs="1"/>
              <xs:element name="Наименование" type="xs:string" minOccurs="1" maxOccurs="1"/>
              <xs:element name="КПП" type="tns:КППТип" minOccurs="0" maxOccurs="1"/>
            </xs:sequence>
          </xs:complexType>
        </xs:element>

        <!-- 3. Сведения о ТС -->
        <xs:element name="ТранспортноеСредство" minOccurs="1" maxOccurs="1">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="Госномер" type="tns:ГосномерТип" minOccurs="1" maxOccurs="1"/>
              <xs:element name="VIN" type="tns:VINТип" minOccurs="1" maxOccurs="1"/>
              <xs:element name="Марка" type="xs:string" minOccurs="1" maxOccurs="1"/>
              <xs:element name="Модель" type="xs:string" minOccurs="1" maxOccurs="1"/>
              <xs:element name="ГодВыпуска" type="xs:gYear" minOccurs="0" maxOccurs="1"/>
            </xs:sequence>
          </xs:complexType>
        </xs:element>

        <!-- 4. Сведения о прицепе (опционально) -->
        <xs:element name="Прицеп" minOccurs="0" maxOccurs="1">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="Госномер" type="tns:ГосномерТип" minOccurs="1" maxOccurs="1"/>
              <xs:element name="VIN" type="tns:VINТип" minOccurs="0" maxOccurs="1"/>
              <xs:element name="Марка" type="xs:string" minOccurs="0" maxOccurs="1"/>
            </xs:sequence>
          </xs:complexType>
        </xs:element>

        <!-- 5. Сведения о водителе -->
        <xs:element name="Водитель" minOccurs="1" maxOccurs="5">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="ФИО" type="tns:ФИОТип" minOccurs="1" maxOccurs="1"/>
              <xs:element name="НомерПрав" type="tns:НомерПравТип" minOccurs="1" maxOccurs="1"/>
              <xs:element name="Стаж" type="xs:integer" minOccurs="0" maxOccurs="1"/>
              <xs:element name="СНИЛС" type="tns:СНИЛСТип" minOccurs="0" maxOccurs="1"/>
            </xs:sequence>
          </xs:complexType>
        </xs:element>

        <!-- 6. Маршрут -->
        <xs:element name="Маршрут" minOccurs="1" maxOccurs="1">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="Пункт" type="tns:ПунктТип" minOccurs="2" maxOccurs="10"/>
            </xs:sequence>
          </xs:complexType>
        </xs:element>
      </xs:sequence>
    </xs:complexType>
  </xs:element>

  <!-- ОПРЕДЕЛЕНИЕ ТИПОВ ДАННЫХ -->

  <xs:simpleType name="ВидПеревозкиТип">
    <xs:restriction base="xs:string">
      <xs:enumeration value="КОММЕРЧЕСКАЯ"/>
      <xs:enumeration value="СОБСТВЕННЫЕ_НУЖДЫ"/>
    </xs:restriction>
  </xs:simpleType>

  <xs:simpleType name="ВидСообщенияТип">
    <xs:restriction base="xs:string">
      <xs:enumeration value="ГОРОДСКОЕ"/>
      <xs:enumeration value="ПРИГОРОДНОЕ"/>
      <xs:enumeration value="МЕЖДУГОРОДНОЕ"/>
      <xs:enumeration value="МЕЖДУНАРОДНОЕ"/>
    </xs:restriction>
  </xs:simpleType>

  <xs:simpleType name="ИННТип">
    <xs:restriction base="xs:string">
      <xs:pattern value="[0-9]{10}|[0-9]{12}"/>
    </xs:restriction>
  </xs:simpleType>

  <xs:simpleType name="ОГРНТип">
    <xs:restriction base="xs:string">
      <xs:pattern value="[0-9]{13}"/>
    </xs:restriction>
  </xs:simpleType>

  <xs:simpleType name="КППТип">
    <xs:restriction base="xs:string">
      <xs:pattern value="[0-9]{9}"/>
    </xs:restriction>
  </xs:simpleType>

  <xs:simpleType name="ГосномерТип">
    <xs:restriction base="xs:string">
      <xs:pattern value="[A-ZА-Я]{1}[0-9]{3}[A-ZА-Я]{2}[0-9]{2,3}"/>
    </xs:restriction>
  </xs:simpleType>

  <xs:simpleType name="VINТип">
    <xs:restriction base="xs:string">
      <xs:pattern value="[A-HJ-NPR-Z0-9]{17}"/>
    </xs:restriction>
  </xs:simpleType>

  <xs:simpleType name="ФИОТип">
    <xs:restriction base="xs:string">
      <xs:minLength value="5"/>
      <xs:maxLength value="100"/>
    </xs:restriction>
  </xs:simpleType>

  <xs:simpleType name="НомерПравТип">
    <xs:restriction base="xs:string">
      <xs:pattern value="[0-9]{2}[A-ZА-Я]{2}[0-9]{6}"/>
    </xs:restriction>
  </xs:simpleType>

  <xs:simpleType name="СНИЛСТип">
    <xs:restriction base="xs:string">
      <xs:pattern value="[0-9]{3}-[0-9]{3}-[0-9]{3} [0-9]{2}"/>
    </xs:restriction>
  </xs:simpleType>

  <xs:complexType name="ПунктТип">
    <xs:sequence>
      <xs:element name="Адрес" type="xs:string" minOccurs="1" maxOccurs="1"/>
      <xs:element name="Широта" type="xs:decimal" minOccurs="0" maxOccurs="1"/>
      <xs:element name="Долгота" type="xs:decimal" minOccurs="0" maxOccurs="1"/>
    </xs:sequence>
  </xs:complexType>

</xs:schema>