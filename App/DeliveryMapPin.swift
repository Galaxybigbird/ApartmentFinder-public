import SwiftUI

/// Keep the precision anchor at the view's bottom center, matching Annotation's .bottom anchor.
struct DeliveryMapPin: View {
    let sequence: String
    let unit: String
    let building: String
    let color: Color
    let selected: Bool
    var estimated = false
    var referenceLabel: String? = nil
    var referenceDisclaimer: String? = nil

    private var primaryLabel: String {
        referenceLabel ?? (building.isEmpty ? unit : "\(unit) · B\(building)")
    }

    private var isMuted: Bool { color == .gray }
    private var badgeForeground: Color { isMuted ? .white : .black }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 5) {
                Text(sequence)
                    .font(.caption2.bold())
                    .monospacedDigit()
                    .foregroundStyle(badgeForeground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.55)
                    .padding(.horizontal, 1.5)
                    .frame(width: 20, height: 20)
                    .background(
                        Circle()
                            .fill(color)
                            .overlay(
                                Circle().stroke(Color.white.opacity(selected ? 0.95 : 0.65), lineWidth: 1)
                            )
                    )
                if estimated {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.caption2)
                        .foregroundStyle(.yellow)
                }
                Text(primaryLabel)
                    .font(.caption.bold())
                    .foregroundStyle(Color.white.opacity(isMuted && !selected ? 0.82 : 1.0))
                    .lineLimit(1)
            }
            .padding(.leading, 4)
            .padding(.trailing, 8)
            .padding(.vertical, 4)
            .background(
                Capsule()
                    .fill(Color.black.opacity(selected ? 0.96 : (isMuted ? 0.80 : 0.90)))
            )
            .overlay(
                Capsule()
                    .stroke(
                        selected ? color : Color.white.opacity(isMuted ? 0.35 : 0.58),
                        lineWidth: selected ? 2.2 : 1.0
                    )
            )
            .shadow(color: Color.black.opacity(selected ? 0.55 : 0.38), radius: selected ? 4 : 2.5, x: 0, y: 1.5)

            if let referenceDisclaimer {
                Text(referenceDisclaimer)
                    .font(.caption2.bold())
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.top, 2)
            }

            // Hairline pointer stem into a deeper, high-precision anchor target dot.
            // Constant layout height keeps bounds identical across selection changes (zero MapKit jitter).
            ZStack(alignment: .top) {
                Capsule()
                    .fill(Color.black.opacity(isMuted && !selected ? 0.60 : 0.85))
                    .frame(width: 4, height: 14)
                    .overlay {
                        Capsule()
                            .fill(
                                LinearGradient(
                                    colors: [selected ? color : Color.white.opacity(isMuted ? 0.70 : 0.95), color],
                                    startPoint: .top,
                                    endPoint: .bottom
                                )
                            )
                            .frame(width: selected ? 2.2 : 1.8, height: 12)
                    }

                ZStack {
                    Circle()
                        .fill(Color.black.opacity(selected ? 0.88 : (isMuted ? 0.58 : 0.75)))
                        .frame(width: 12, height: 12)
                    Circle()
                        .fill(Color.white.opacity(isMuted && !selected ? 0.85 : 1.0))
                        .frame(width: 10, height: 10)
                    Circle()
                        .fill(color)
                        .frame(width: selected ? 7 : 6.5, height: selected ? 7 : 6.5)
                }
                .background {
                    if selected {
                        Circle()
                            .fill(color.opacity(0.30))
                            .overlay(Circle().stroke(color.opacity(0.95), lineWidth: 1.5))
                            .frame(width: 16, height: 16)
                    }
                }
                .frame(width: 18, height: 12)
                .padding(.top, 10)
            }
            .frame(height: 22)
        }
        .fixedSize()
        .frame(minWidth: 44)
        .contentShape(Rectangle())
    }
}
