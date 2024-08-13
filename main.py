import asyncio
from bleak import BleakScanner, BleakClient

# UUID
SERVICE_UUID = "e9b49a22-4c8a-4bc1-a163-baf7a7a07b1b"
CHARACTERISTIC_UUID = "e9b49a22-4c8a-4bc1-a163-baf7a7a07b1b"

detected_devices = {}
target_device_address = None

def scan_callback(device, advertisement_data):
    global detected_devices, target_device_address
    detected_devices[device.address] = device.name
    if SERVICE_UUID in advertisement_data.service_uuids:
        target_device_address = device.address
        print(f"Found target device: {device.name} - {device.address}")
        return False

async def scan_devices(scanner):
    print("=== Starting new scan ===")
    await scanner.start()
    await asyncio.sleep(2)
    await scanner.stop()
    print("\n=== Scan complete ===")

async def connect_and_interact(address):
    async with BleakClient(address) as client:
        print(f"Connected to {address}")

        data_to_write = bytearray([0x01, 0x02, 0x03, 0x04])
        await client.write_gatt_char(CHARACTERISTIC_UUID, data_to_write)
        print(f"Written value: {data_to_write.hex()}")

        await asyncio.sleep(1)

        characteristic_value = await client.read_gatt_char(CHARACTERISTIC_UUID)
        print(f"Read value after initial write: {characteristic_value.hex()}")

        new_data_to_write = bytearray([0x05, 0x06, 0x07, 0x08])
        await client.write_gatt_char(CHARACTERISTIC_UUID, new_data_to_write)
        print(f"Written new value: {new_data_to_write.hex()}")


        await asyncio.sleep(1)

        characteristic_value = await client.read_gatt_char(CHARACTERISTIC_UUID)
        print(f"Read value after new write: {characteristic_value.hex()}")

async def main():
    global target_device_address

    scanner = BleakScanner(detection_callback=scan_callback)
    await scan_devices(scanner)

    if target_device_address:
        print(f"Connecting to device: {target_device_address}")
        await connect_and_interact(target_device_address)
    else:
        print("Target device not found")

if __name__ == "__main__":
    asyncio.run(main())
