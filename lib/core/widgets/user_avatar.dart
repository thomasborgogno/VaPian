import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';

/// Google profile photo when signed in with Google, a generic person icon
/// for guests (or if Google didn't return a photo).
class UserAvatar extends StatelessWidget {
  const UserAvatar({super.key, required this.user, this.radius = 16});

  final User? user;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final photoUrl = user?.photoURL;
    if (photoUrl != null) {
      return CircleAvatar(radius: radius, backgroundImage: NetworkImage(photoUrl));
    }
    return CircleAvatar(
      radius: radius,
      child: Icon(Icons.person, size: radius),
    );
  }
}
