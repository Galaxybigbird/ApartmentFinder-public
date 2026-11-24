import XCTest
import SwiftUI
import MapKit
import ApartmentCore
@testable import ApartmentFinder

@MainActor
final class NativeDeliveryMapTests: XCTestCase {
    private func model(selected: Bool = false, heading: Double = 0,
                       select: @escaping (String) -> Void = { _ in }) -> NativeDeliveryMap {
        NativeDeliveryMap(pins: [.init(id: "stop|place", stopID: "stop", placeID: "place",
            coordinate: .init(latitude: 36.01, longitude: -115.18), sequenceID: 7, sequence: "7", unit: "1158",
            building: "12", status: "Estimated shared entry", ready: false, completed: false,
            selected: selected, estimated: true, referenceLabel: "1158 · B12", radius: 10, frontage: [])],
            areas: [], community: nil, locker: nil, userLocation: nil, bearing: nil,
            camera: .camera(MapCamera(centerCoordinate: .init(latitude: 36.01, longitude: -115.18), distance: 350, heading: heading)),
            style: .standard, select: select, userMoved: { _ in }, cameraSettled: { _ in })
    }

    func testPanningKeepsTheSameNativePinAndItsCoordinateAnchor() throws {
        let model = model()
        let coordinator = model.makeCoordinator()
        let map = coordinator.makeMapView()
        map.frame = CGRect(x: 0, y: 0, width: 390, height: 500)
        coordinator.update(map, model: model)
        let pin = try XCTUnwrap(map.annotations.first { $0 is NativeDeliveryMap.Annotation })
        let view = try XCTUnwrap(coordinator.mapView(map, viewFor: pin))
        XCTAssertEqual(view.centerOffset.y, -view.bounds.height / 2, accuracy: 0.5)
        XCTAssertGreaterThan(view.bounds.width, 43)
        XCTAssertGreaterThan(view.bounds.height, 43)
        XCTAssertTrue(view.accessibilityLabel?.contains("unit 1158,") == true)
        XCTAssertFalse(view.accessibilityLabel?.contains("1157/1158") == true)
        let before = map.convert(pin.coordinate, toPointTo: map)
        map.setCenter(.init(latitude: 36.0105, longitude: -115.1795), animated: false)
        coordinator.mapViewDidChangeVisibleRegion(map)
        XCTAssertTrue(map.annotations.contains { ($0 as AnyObject) === (pin as AnyObject) })
        XCTAssertNotEqual(map.convert(pin.coordinate, toPointTo: map), before)
        // A model refresh with the same camera request must preserve the user's pan.
        let panned = map.centerCoordinate
        coordinator.update(map, model: model)
        XCTAssertEqual(map.centerCoordinate.latitude, panned.latitude, accuracy: 0.000001)
        XCTAssertEqual(map.centerCoordinate.longitude, panned.longitude, accuracy: 0.000001)
        // Resume-follow is an explicit new command even if its target equals
        // the camera request issued before the pan.
        var resumed = model
        resumed.cameraCommand = 1
        coordinator.update(map, model: resumed)
        XCTAssertEqual(map.centerCoordinate.latitude, 36.01, accuracy: 0.000001)
        XCTAssertEqual(map.centerCoordinate.longitude, -115.18, accuracy: 0.000001)
    }

    func testAutomaticFitShowsDistantPinsInAShortHeadingUpViewport() {
        var route = model(heading: 270)
        route.automaticFitRadius = 250
        let coordinator = route.makeCoordinator()
        let map = coordinator.makeMapView()
        map.frame = .zero
        coordinator.update(map, model: route)
        // UIKit can lay out the native view after SwiftUI sends its first camera command.
        map.frame = CGRect(x: 0, y: 0, width: 320, height: 239)
        map.setNeedsLayout()
        map.layoutIfNeeded()

        let clearArea = map.bounds.insetBy(dx: 60, dy: 75)
        for coordinate in [
            CLLocationCoordinate2D(latitude: 36.0115, longitude: -115.18),
            CLLocationCoordinate2D(latitude: 36.01, longitude: -115.1822)
        ] {
            XCTAssertTrue(clearArea.contains(map.convert(coordinate, toPointTo: map)))
        }
        XCTAssertEqual(map.camera.heading, 270, accuracy: 1)
        XCTAssertGreaterThan(map.camera.centerCoordinateDistance, 350)
    }

    func testSelectionUpdatesExistingPinAndKeepsTheManifestTapTarget() throws {
        var selected: String?
        let frontage = [
            Coordinate(latitude: 36.01, longitude: -115.1801),
            Coordinate(latitude: 36.01, longitude: -115.1799)
        ]
        func makeWithFrontage(selected isSelected: Bool) -> NativeDeliveryMap {
            NativeDeliveryMap(pins: [.init(id: "stop|place", stopID: "stop", placeID: "place",
                coordinate: .init(latitude: 36.01, longitude: -115.18), sequenceID: 7, sequence: "7", unit: "1158",
                building: "12", status: "Estimated shared entry", ready: false, completed: false,
                selected: isSelected, estimated: true, referenceLabel: "1158 · B12", radius: 10, frontage: frontage)],
                areas: [], community: nil, locker: nil, userLocation: nil, bearing: nil,
                camera: .camera(MapCamera(centerCoordinate: .init(latitude: 36.01, longitude: -115.18), distance: 350, heading: 0)),
                style: .standard, select: { selected = $0 }, userMoved: { _ in }, cameraSettled: { _ in })
        }
        let first = makeWithFrontage(selected: false)
        let coordinator = first.makeCoordinator()
        let map = coordinator.makeMapView()
        coordinator.update(map, model: first)
        let pin = try XCTUnwrap(map.annotations.first { $0 is NativeDeliveryMap.Annotation })

        // Verify outer highlighted zone, deeper 2.2m inner core, and frontage line before selection.
        let initialCircles = map.overlays.compactMap { $0 as? MKCircle }
        let initialLines = map.overlays.compactMap { $0 as? MKPolyline }
        XCTAssertEqual(Set(initialCircles.compactMap { $0.title }), ["zone", "core"])
        XCTAssertEqual(initialLines.compactMap { $0.title }, ["frontage"])
        let initialCore = try XCTUnwrap(initialCircles.first { $0.title == "core" })
        XCTAssertEqual(initialCore.radius, 2.2, accuracy: 0.01)

        // Verify selection updates existing pin, refreshes overlays to deeper selected styles, and keeps tap target.
        coordinator.update(map, model: makeWithFrontage(selected: true))
        XCTAssertTrue(map.annotations.contains { ($0 as AnyObject) === (pin as AnyObject) })
        let selectedCircles = map.overlays.compactMap { $0 as? MKCircle }
        let selectedLines = map.overlays.compactMap { $0 as? MKPolyline }
        XCTAssertEqual(Set(selectedCircles.compactMap { $0.title }), ["zone-selected", "core-selected"])
        XCTAssertEqual(selectedLines.compactMap { $0.title }, ["frontage-selected"])
        let selectedLineRenderer = try XCTUnwrap(coordinator.mapView(map, rendererFor: try XCTUnwrap(selectedLines.first)) as? MKPolylineRenderer)
        XCTAssertEqual(selectedLineRenderer.lineWidth, 4.5, accuracy: 0.01)

        let view = try XCTUnwrap(coordinator.mapView(map, viewFor: pin))
        coordinator.mapView(map, didSelect: view)
        XCTAssertEqual(selected, "stop")
        XCTAssertEqual(view.accessibilityIdentifier, "mini.pin.7.place")
        XCTAssertEqual(view.displayPriority, .required)
        XCTAssertEqual(view.zPriority, .max)
    }
}
