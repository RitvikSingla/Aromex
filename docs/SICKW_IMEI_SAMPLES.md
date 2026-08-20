# SICKW IMEI Check — Sample Result Formats (per brand / service)

> Reference: the sample output each [sickw.com](https://sickw.com/) IMEI/serial service returns.
> When you submit an IMEI/SN to a service, you get a result in the format shown below.
> Captured from the per-service "sample" panels on the site.
>
> API endpoint pattern:
> `https://sickw.com/api.php?format=$format&key=$api&imei=$imei&service=$service`
> (valid 31-char API key required; free tier ~5 checks/day).
>
> **Note:** values are the site's own example values with trailing IMEI/SN digits
> masked as `XXX`/`xxx`. Prices/success-rates are indicative and change over time.

## Contents
- [Android / other phone brands](#android--other-phone-brands)
- [Apple / iPhone services](#apple--iphone-services)
- [Utility / identifier services](#utility--identifier-services)
- [Full service catalog (IDs)](#full-service-catalog-service-ids)

---

# Android / other phone brands

## SAMSUNG INFO (`service=80`)
```
Search Term:      35077953258XXXX
IMEI1:            35077953258XXXX
Serial Number:    RFCTB1FXXXX
Model Number:     SM-S906UZKAATT
Model Name:       SM-S906U
Model Desc:       Galaxy S22+ 5G
Production Date:  24 November 2022
Warranty Until:   24 November 2023
Warranty Status:  Warranty Active
Carrier:          AT&T United States
```

## SAMSUNG INFO - PRO (`service=1`)
```
Search Term:      35212672410XXXX
IMEI1:            35212672410XXXX
IMEI2:            35299219410XXXX
Serial Number:    RFCT703XXXX
DO Number:        711375XXXX(C4B0-1C)
SKU:              SM2G990BLGDEUE
Manufacturer:     Samsung Electronics Vietnam (SEV), Bac Ninh City
Full Name:        MOBILE SM-G990B LIGHT GREEN EUE
Model Number:     SM-G990BLGDEUE
Model Name:       SM-G990B/DS
Model Description: Galaxy S21 FE 5G
Production Date:  05 July 2022
Warranty Until:   05 July 2023
Warranty Status:  Warranty Active
Sales Buyer:      Orange Romania
Carrier:          Open
Sold By Country:  Austria
Ship To Country:  Romania
Sold Date:        05 July 2022
Ship Date:        13 July 2022
```

## SAMSUNG KNOX GUARD INFO (`service=82`)
```
Search Term:      35212672410XXXX
IMEI1:            35212672410XXXX
IMEI2:            35299219410XXXX
Serial Number:    RFCT703XXXX
Model:            SM-G990B/DS (Galaxy S21 FE 5G)
Manufacturer:     Samsung Electronics Vietnam (SEV), Bac Ninh City
Warranty Until:   05 July 2023
Warranty Status:  Warranty Active

# When Knox Guard OFF:
Knox Guard:       OFF

# When Knox Guard ON:
Knox Guard:       ON
Device Id:        641071d4394xxcef9d34a33xx
KG Status:        Locked
KG Message:       This device is lost or stolen please contact claimvalidation@squaretrade.com
```

## HUAWEI INFO (`service=15`)  (also covers Honor legacy)
```
Model Description / Model Code / External Code / Device Type
IMEI / Serial Number (S/N) / PCBA Barcode
MEID (HEX variants)
MAC addresses (WiFi, Bluetooth)
Item Code / Customer Code
Country Name / Company Name
Offer Code / Contract Code / Level codes
Warranty Status (e.g. Out of Warranty)
Warranty Type / duration / Valid country / Service codes
Dates: Start / End / Bind / Order / Shipment / Delivery / Activation
```

## HONOR INFO (`service=73`)
```
Model:               HONOR 50 8GB+256GB Emerald Green Single Card Claro Ver. US Charger
IMEI:                8628020501xxx
SN:                  AL9XVB1A2300xxx
Item Code:           5109AAXL
Offer Code:          OFFE00270669
Purchase Country:    Guatemala
Warranty Status:     In Warranty
Warranty Start Date: 2021/12/3
Warranty End Date:   2023/12/5
Warranty coverage breakdown:
  1 Year Device Warranty        (2021/12/3 - 2022/12/4)
  7 Day Device Return           (2021/12/3 - 2021/12/10)
  7 Day Device Replacement      (2021/12/3 - 2021/12/10)
  HONOR Extended Warranty 12M   (2022/12/4 - 2023/12/5)
  HONOR Screen Protection 3M    (2021/12/3 - 2022/3/6)
```

## MOTOROLA INFO (`service=13`)
```
Product Name:       MOTO Phone XT1944-3 MX 2+16 GR SSL AT&T
IMEI:               354122090334xxx
Serial:             TF3KJG2G9R
Model:              Moto XT1944-3
Machine Type:       PACD
Product Id:         PACD0000MX
MTM:                PACD0000MX
Transceiver:        SA78C28282
Ship Date:          2018-04-23
Ship to Country:    Mexico
Sold to Country:    Mexico
Warranty Remaining: 19 days
Warranty Start:     2018-04-23
Warranty End:       2019-04-22
(Phone, Charger/USB, Battery, Earphone warranty each listed)
```

## LENOVO INFO (`service=22`)
```
Product Name:       Lenovo Phone A2016b30 MX 8G BL GL AT_T
IMEI:               862569038355xxx
IMEI2:              862569038355xxx
Serial:             HA0PLLxx
Model:              Moto A2016b30
Machine Type:       PA4U
Product Id:         PA4U0028MX
MTM:                PA4U0028MX
Transceiver:        PA4U
Ship Date:          2016-10-30
Ship to Country:    Mexico
Sold to Country:    Mexico
Color:              Black
Warranty Remaining: 0 days
Warranty Start:     2016-10-30
Warranty End:       2017-11-08
Status:             Out Of Warranty
```

## GOOGLE PIXEL INFO (`service=42`)
```
Description
Model
Model Number
IMEI
Serial Number
Purchase Country
Warranty Status
Warranty Start Date
Warranty End Date
Device Protection End Date
Device Age
# Example: Pixel 3a 64GB Just Black, United States, warranty May 2019–2020 (expired),
#          device protection ends May 2021, ~1 year old.
```

## VIVO | IQOO INFO (`service=75`)
```
IMEI Number:              8685190632XXXX
Manufacturer:             Vivo Mobile Communication Co Ltd
Model Name:               V25 Pro 8G+128G
Model Color:              Sailing Blue
Contract Status:          Not Active
Warranty Expiration Date: 23/10/2023
```

## OPPO | ONEPLUS | REALME INFO (`service=39`)
```
Brand:             OPPO
Model:             A3xPK(4+128)
Model Description: Mobile phone
RAM:               4GB
ROM:               128
IMEI:              8686710721xxxx
IMEI2:             8686710721xxxx
PCB:               00233113471229401xxx
SKU:               11001034xxx
Chip SN:           f5ffxxx
Adaptor SN:        D*61140100xxxx1113810
Battery SN:        X*611303000xxxE169554W2
Software Version:  V2.9
Product Version:   VK.5
Phone Status:      Normal Delivery
Project No:        23311
Material No:       6310xxx004063
Delivery Date:     2024-11-18 15:19:26
Production Date:   2024-10-04 17:31:00
Manufacturing Date: 2024-11-18 09:19:26
Registration Date: 2025-07-22 19:19:13
Warranty End Date: 2026-07-21 19:19:13
Warranty Validity: 12 months
Purchase Country:  Pakistan
```

## ZTE | NUBIA | REDMAGIC INFO (`service=55`)
```
IMEI:              86994404011xxx
MSN:               320704805014
Material Code:     126658901004
Board Tracking No: 728040814159
Manufacturer:      ZTE
Model Code:        ZTE 9000
Model Name:        Blade V 2020
Model Description: P671F60//ZTE 9000 Mobile Phone(Mexico/Telcel/White/MT6771/4+128G)
Product Line:      Oversea Mobile Phone
Production Date:   2020-04-29 22:20:22
Warranty Status:   Out of Warranty
```

## ITEL | INFINIX | TECNO | SONIM INFO (`service=45`)
```
Model Description / Model Name / Market Model
Memory (e.g. 256+12 GB)
IMEI / IMEI2 / Serial
Version Code / Factory Code / Material Code
Box number / Invoice number
Warranty Status (e.g. Active) / Activation date / Expiration date
Activation & Sales dates (timestamps)
Buyer Name (e.g. Flipkart India Private Limited)
Sold By / Ship To / Activation countries
```

## XIAOMI | REDMI | POCO — MI LOCK ON/OFF (`service=206`)
```
Manufacturer:     Xiaomi Communications Co Ltd
Model Name:       Redmi Note 9 Pro
Model Code:       M2003J6xxx
IMEI Number:      864131057209xxx
Serial Number:    27957/20VH00xxx
Description:      Redmi Note 9 Pro Interstellar Grey 6G RAM 128G ROM
Product ID:       27957
Purchase Country: Libya
Production Date:  2020-08-27 21:25:27
Activation Date:  2020-09-11 08:19:21
MI Activation Lock: ON
Email Id Hint:    abd***@gmail.com
Phone Number Hint: +218913****29
# Input accepts: IMEI, SN or Unlock Number (10 chars)
```

## XIAOMI | REDMI | POCO — MI LOCK CLEAN/LOST (`service=58`)
```
Model Name:          Redmi Note 11 Pro 5G Graphite Gray 6GB RAM 128GB ROM
Model Code:          2201116SG
Search Term:         86067706390xxxx
IMEI Number:         86067706390xxxx
IMEI2 Number:        8606770639xxxx
Serial Number:       38081/K2Q70xxx
Unlock Number:       KN210K00xxx
SKU Number:          MZB0xxx
Purchase Country:    United Kingdom
Activation Country:  United Kingdom
Warranty Status:     Non-Local device
Warranty Description: Non-local sale and Non-global warranty
Warranty Start:      2022-06-16 06:01:40
Warranty End:        2024-06-15
Delivery Date:       2022-03-25 15:31:06
Activation Date:     2022-03-25 15:31:06
Production Date:     2022-03-10 17:09:45
MI Activation Lock:  ON
MI Activation Status: Lost
```

## ASUS INFO (`service=34`)
```
Model Name:          ROG Phone II (ZS660KL)
Model Series:        PREMIUM PHONE(System)
Model Number:        ZS660KL-S855P-12G512G-BK
IMEI:                3582961008735xx
IMEI2:               3582961008735xx
Serial:              KAAIKN07U9xxFW5
Distributor:         ESHOP
Purchase Country:    United States
Warranty Status:     Expired
Warranty Start Date: 2020-01-05
Warranty End Date:   2021-01-09
```

## ACER INFO (`service=23`)
```
Search Term:            35245306595xxxx
Category Id:            C27S
Serial Number:          HMHJLSN0024500623Cxxx
IMEI:                   35245306595xxxx
SNID:                   45002514xxx
Warranty Validity Flag: True
Warranty Expiry Date:   2016-02-23T00:00:00
Purchase Date:          23/02/2015
Production Date:        2014-12-25T00:00:00
Warranty Classification: Out of Warranty
Warranty Months:        12
Warranty Desc:          1 Year Carry In
Country Of Sale:        Indonesia
Product Line Desc:      Smart Handheld
```

## KYOCERA INFO (`service=43`)
```
Manufacturer:            Kyocera
Model Name:              DuraXV Extreme E4810
IMEI:                    015588002408xxx
Carrier:                 VerizonXX
Phone Category:          Feature Rugged
Limited Warranty Period: 730
```

## ALCATEL INFO (`service=17`)
```
Description:       One Touch Pixi 4 (6) 4G
Model:             Alcatel 5098S
IMEI:              0146480006593xx
IMEI2:             0146480006593xx
Serial:            18KE07CP2EA01xx
Provider ID:       5098S-2BALUxx
BT MAC:            CCFD17C582xx
WI-FI MAC:         CCFD17B221xx
Part Number:       SAA1FA4AA1xx
Purchase Country:  United States
Software Version:  D36
Warranty Status:   Out of Warranty
Warranty End Date: 21-10-2018
```

## NOTHING PHONE INFO (`service=233`)  (Nothing / CMF)
```
Search Input:          357269622300xxx
Type:                  Phone
Serial:                00117649P0xxx
SKU:                   A10400117
Purchase Country:      India
Product Model:         CMF Phone 1
Product Configuration: IN version 6+128GB
Product Color:         Light Green
IMEI1:                 357269622xxxx
IMEI2:                 357269622xxxxx
Activation Date:       2025-03-26
```

## SPARX INFO (`service=86`)
```
IMEI:                35490594016xxx
Manufacturer:        SPARX
Model Code:          EDGE 20 PRO
Warranty Status:     Active
Warranty Start Date: 29 March 2024
Warranty End Date:   28 March 2025
```

## DOOGEE INFO (`service=56`)
```
IMEI1:              3561744300203xx
IMEI2:              3561744300203xx
Manufacturer:       KVD International Group Limited
Model Name:         S96Pro
Project Code:       boruizhiheng6785_10.0_S96Pro_en-US_other
Source Version:     S9S88A7.DGE.DOOGEE.EEA.HB.HJ.AYYDVFAZ.0917.V3.01_...
Target Version:     S9S88A7.DGE.DOOGEE.EEA.HB.HJ.AYYDVFAZ.0917.V3.01_...
Sold to Continent:  North America
Sold to Country:    United States
Sold to Region:     California
Activation Date:    2021-02-05 10:00:26
```

## UNITY CELLECOR INFO (`service=87`)
```
Material Code / Material Name / Invoice Date
Serial Numbers (2)
Warranty: Start / End dates / Status / Code / Days
Customer: Name / Address / City / State / Country / Pincode / Mobile
Activation & abandonment status
Accessory warranty status
DAP Applicable: Yes / DOA Applicable: No
Call Code / Repair Date / Repaired By
Symptom Description / Defect Description / Repair Description
```

## HTC INTERNATIONAL (`service=115`)  (unlock code service)
```
IMEI: 3547730727799xx
Code: 77442699
# Codes provided from HTC database; appears in Order History in 5–15 min.
```

## BRAND & MODEL INFO (`service=203`)  (any GSM device)
```
IMEI:  352073065544xxx
Brand: Apple Inc
Model: Apple iPhone 6 (A1586)
```

---

# Apple / iPhone services

## iPHONE CARRIER (`service=103`)
Provides carrier & SIM-lock status, warranty and extra info. Supported: IMEI/MEID/SN — all Apple GSM devices.

**Locked device — fields returned:**
```
Model / IMEI / IMEI2 / MEID / Serial Number
Warranty Status / Purchase Date
Demo / Loaner / Replaced / Replacement / Refurbished
Purchase Country
Locked Carrier   (SIM-Lock = Locked)
```
**Unlocked device — fields returned:**
```
Model / IMEI / IMEI2 / MEID / Serial Number
Warranty Status
Demo / Loaner / Replaced / Replacement / Refurbished
Purchase Country
SIM-Lock Status  (= Unlocked)
```

## iPHONE CARRIER & FMI (`service=78`)
```
Model:                        IPHONE 14 STARLIGHT 128GB-USA
IMEI:                         35883259944XXXX
IMEI2:                        35883259862XXXX
Serial Number:                V9267DXXXX
Warranty Status:              Apple Limited Warranty
Telephone Technical Support:  Active (expires 2023-03-10)
Repairs and Service Coverage: Active (expires 2023-12-09)
Activation Status:            Activated
Registration Status:          Registered
iCloud Lock:                  ON
Sim-Lock Status:              Locked
Locked Carrier:               23 - US AT&T Activation Policy
AppleCare Eligible:           Yes
Estimated Purchase Date:      2022-12-10
Purchase Country:             United States
Demo / Loaner / Replaced / Refurbished: No
```

## iPHONE SIM-LOCK (`service=8`)
Returns SIM-lock state (Locked / Unlocked) for the submitted IMEI/SN.

## iPHONE MODEL COLOR & CAPACITY (`service=92`)
```
IMEI Number:       35407355160XXXX
IMEI2 Number:      35407355188XXXX
Model Description: iPhone 13 Pro 128GB Graphite
```

## APPLE SERIAL INFO (`service=26`)
```
Serial Number: DNPV506UHXXX
Model Desc:    iPhone 7 256GB Black SM A1778
Model Name:    iPhone 7
Model Number:  A1778
Model iD:      iPhone9,1
Capacity:      256GB
Color:         Black
Type:          MM
Year:          2017
Week:          31 (31.07 - 06.08)
Factory:       Foxconn Chengdu China
# Note: Apple devices sold after 2021 not supported by this service.
```

## APPLE SOLD BY & COUNTRY INFO (`service=105`)
```
Description:             IPHONE 13 PRO SILVER 128GB-GBR
Model:                   IPHONE 13 PRO ROW 128GB SILVER
IMEI Number:            350060424216xxx
IMEI2 Number:           350060423729xxx
MEID Number:            35006042421xxx
Serial Number:          XXX6LY7NNQ
Find My iPhone:         OFF
Replaced Device:        NO
Sim-Lock:               Unlocked
Coverage Status:        Apple Limited Warranty
Product Sold By:        SKY UK LIMITED
Purchase Country:       United Kingdom
Estimated Purchase Date: 12 February 2022
Coverage Start:         12 February 2022
Coverage End:           11 February 2023
```

## APPLE GSX PREMIUM DETAILS (`service=63`)
```
Serial / IMEI / IMEI2 / MEID
Configuration code / Product description
Purchase details (date, country, seller)
Warranty status & coverage / Activation dates & policies
Restore history / OS build info
iCloud Lock / MDM Lock / SIM Lock status
iCloud account status (e.g. Lost, Active)
Wireless MAC address / Carrier policy assignments
Case history (dates + summaries)
Replacement history (prior devices + serials)
```

## iCLOUD ON/OFF (`service=3`)
```
354376067505XXX   Find My iPhone: OFF
356133314400XXX   Find My iPhone: ON
```

## APPLE MDM STATUS (`service=81`)
```
# MDM disabled:
IMEI Number:   356079095896xxx
Serial Number: F4HX87GMxxx
MDM Lock:      OFF

# MDM active:
IMEI Number:   356079095896xxx
Serial Number: F4HX87GMxxx
MDM Lock:      ON
```

---

# Utility / identifier services

## SIM ICCID | IMSI INFO (`service=37`)
```
# By IMSI:
IMSI:    2780112904889831
Network: Vodafone
Country: Malta

# By ICCID:
ICCID:   8901260752799476464
Network: TMobile
Country: United States
```

## IMEI ⇄ SN CONVERT (`service=12`)
Converts an Apple IMEI to its serial number (or vice versa).

---

# Full service catalog (service IDs)

All service IDs seen on the site, for building `service=` URLs.

### Apple
| ID | Service |
|----|---------|
| 105 | Apple Sold By & Country Info |
| 63 | Apple GSX Premium Details |
| 146 | Apple GSX Premium - Promo |
| 29 | Apple Replacements History |
| 77 | Apple GSX Repair Eligibility |
| 68 | Apple GSX Cases & Repairs |
| 219 | Apple Part Number - MPN |
| 103 | iPhone Carrier |
| 78 | iPhone Carrier & FMI |
| 61 | iPhone Carrier & FMI & Blacklist |
| 147 | iPhone Carrier & FMI & Blacklist - S2 |
| 72 | Apple Carrier + MDM & iCloud & GSX Status |
| 92 | iPhone Model Color & Capacity |
| 12 | IMEI ⇄ SN Convert |
| 26 | Apple Serial Info |
| 3 | iCloud On/Off |
| 101 | Apple Activation Status |
| 88 | Apple Activation Status - Pro |
| 85 | Apple Demo Devices Info |
| 81 | Apple MDM Status |
| 40 | Apple MDM & iCloud Status |
| 110 | MacBook & iMac iCloud On/Off Status |
| 66 | iPhone & Mac iCloud Clean/Lost Status |

### Brand / device info
| ID | Service |
|----|---------|
| 34 | ASUS Info |
| 23 | Acer Info |
| 73 | Honor Info |
| 86 | Sparx Info |
| 56 | Doogee Info |
| 15 | Huawei Info |
| 22 | Lenovo Info |
| 43 | Kyocera Info |
| 80 | Samsung Info |
| 1 | Samsung Info - Pro |
| 82 | Samsung Knox Guard Info |
| 17 | Alcatel Info |
| 13 | Motorola Info |
| 75 | Vivo / iQOO Info |
| 42 | Google Pixel Info |
| 233 | Nothing Phone Info |
| 203 | Brand & Model Info |
| 87 | Unity Cellecor Info |
| 55 | ZTE / Nubia / RedMagic Info |
| 39 | Oppo / OnePlus / Realme Info |
| 45 | itel / Infinix / Tecno / Sonim Info |
| 206 | Xiaomi / Redmi / Poco — MI Lock On/Off |
| 58 | Xiaomi / Redmi / Poco — MI Lock Clean/Lost |
| 115 | HTC International (unlock code) |
| 37 | SIM ICCID / IMSI Info |
| 82 | Samsung Knox Guard Info |

### Carrier status / unlock / blacklist
| ID | Service |
|----|---------|
| 65 | AT&T USA Status & Unlock |
| 16 | T-Mobile USA Status - Pro |
| 21 | Cricket USA Status - Pro |
| 232 | Verizon USA Status - Pro |
| 230 | Xfinity USA Status - Pro |
| 231 | Cspire USA Status - Pro |
| 220 | TracFone USA Status - Pro |
| 6 | WW Blacklist Status - Pro |
| 54 | WW Blacklist Status |
| 24 | Brazil Blacklist Status |
| 20 | Korea Blacklist Status |
| 32 | Japan Blacklist Status |
| 31 | Australia Blacklist Status |
| 94 | MDM Lock Bypass - iPhone/iPad |

_Regional carrier-clean/premium services (Austria A1, Canada Fido/Rogers/Telus/Videotron, Claro, EMEA, Hungary Vodafone, Japan AU/DoCoMo/Softbank, Philippines Globe, Romania Orange/Vodafone, UK EE/O2/Vodafone, US AT&T/Cricket/C-Spire/Reseller Flex, France Bouygues, Samsung Europe/UK) also exist — IDs 50–144 range._

---

_Source: https://sickw.com/ (per-service sample panels) and https://sickw.com/sample.txt_
